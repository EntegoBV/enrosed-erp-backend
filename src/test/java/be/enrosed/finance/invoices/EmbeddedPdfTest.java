package be.enrosed.finance.invoices;

import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class EmbeddedPdfTest {
    static final byte[] PDF=("%PDF-1.7\n1 0 obj << /Type /Catalog >> endobj\n% a small but real-looking body that wraps over "
            +"several base64 lines\ntrailer << /Root 1 0 R >>\n%%EOF\n").getBytes(StandardCharsets.US_ASCII);
    static final String UBL_NS=" xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:Invoice-2\""
            +" xmlns:cac=\"urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2\""
            +" xmlns:cbc=\"urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2\"";

    static String lines(byte[] bytes) { return Base64.getMimeEncoder(24,"\r\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(bytes); }
    static String ublObject(String mime,String filename,String payload) {
        return "<cac:AdditionalDocumentReference><cbc:ID>1</cbc:ID><cac:Attachment>"
                +"<cbc:EmbeddedDocumentBinaryObject mimeCode=\""+mime+"\""+(filename==null?"":" filename=\""+filename+"\"")+">"
                +payload+"</cbc:EmbeddedDocumentBinaryObject></cac:Attachment></cac:AdditionalDocumentReference>";
    }
    static byte[] ubl(String... objects) {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Invoice"+UBL_NS+"><cbc:ID>VT-2026-0412</cbc:ID>"
                +String.join("",objects)+"</Invoice>").getBytes(StandardCharsets.UTF_8);
    }

    @Test void aUblInvoiceGivesItsEmbeddedPdfWithASafeName() {
        byte[] xml=ubl(ublObject("application/pdf","VT 2026/0412.pdf",lines(PDF)));
        var extracted=EmbeddedPdf.fromXml(xml).orElseThrow();
        assertArrayEquals(PDF,extracted.pdf());
        assertEquals("VT 2026_0412.pdf",extracted.filename());
        assertTrue(lines(PDF).contains("\r\n"),"the fixture really carries line breaks");
    }

    @Test void aCiiInvoiceGivesItsAttachmentBinaryObject() {
        String cii="<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                +"<rsm:CrossIndustryInvoice xmlns:rsm=\"urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100\""
                +" xmlns:ram=\"urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100\">"
                +"<rsm:SupplyChainTradeTransaction><ram:ApplicableHeaderTradeAgreement><ram:AdditionalReferencedDocument>"
                +"<ram:AttachmentBinaryObject mimeCode=\"Application/PDF\" filename=\"factuur\">\n"+lines(PDF)+"\n"
                +"</ram:AttachmentBinaryObject></ram:AdditionalReferencedDocument></ram:ApplicableHeaderTradeAgreement>"
                +"</rsm:SupplyChainTradeTransaction></rsm:CrossIndustryInvoice>";
        var extracted=EmbeddedPdf.fromXml(cii.getBytes(StandardCharsets.UTF_8)).orElseThrow();
        assertArrayEquals(PDF,extracted.pdf());
        assertEquals("factuur.pdf",extracted.filename(),"mimeCode is case-blind and the name is forced to .pdf");
    }

    @Test void aNonPdfObjectIsSkippedAndTheNextPdfIsUsed() {
        byte[] png=new byte[]{(byte)0x89,'P','N','G',13,10,26,10};
        byte[] fake="not a pdf at all".getBytes(StandardCharsets.US_ASCII);
        byte[] xml=ubl(ublObject("image/png","logo.png",lines(png)),ublObject("application/pdf","fake.pdf",lines(fake)),
                ublObject("application/pdf","real.pdf",lines(PDF)));
        var extracted=EmbeddedPdf.fromXml(xml).orElseThrow();
        assertArrayEquals(PDF,extracted.pdf());assertEquals("real.pdf",extracted.filename());
        assertNull(EmbeddedPdf.fromXml(ubl(ublObject("application/pdf",null,lines(PDF)))).orElseThrow().filename());
    }

    @Test void doctypesAndExternalEntitiesAreRefusedWithoutTouchingFilesOrTheNetwork() throws Exception {
        Path secret=Files.createTempFile("embedded-pdf-secret-",".txt");
        Files.writeString(secret,Base64.getEncoder().encodeToString(PDF));
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(300);
            String url="http://127.0.0.1:"+server.getLocalPort()+"/evil.dtd";
            String fileEntity="<?xml version=\"1.0\"?><!DOCTYPE Invoice [<!ENTITY xxe SYSTEM \""+secret.toUri()+"\">]>"
                    +"<Invoice"+UBL_NS+">"+ublObject("application/pdf","x.pdf","&xxe;")+"</Invoice>";
            String externalDtd="<?xml version=\"1.0\"?><!DOCTYPE Invoice SYSTEM \""+url+"\"><Invoice"+UBL_NS+">"
                    +ublObject("application/pdf","x.pdf",lines(PDF))+"</Invoice>";
            String parameterEntity="<?xml version=\"1.0\"?><!DOCTYPE Invoice [<!ENTITY % remote SYSTEM \""+url+"\"> %remote;]>"
                    +"<Invoice"+UBL_NS+">"+ublObject("application/pdf","x.pdf",lines(PDF))+"</Invoice>";
            String laughs="<?xml version=\"1.0\"?><!DOCTYPE lolz [<!ENTITY lol \"lol\"><!ENTITY lol2 \"&lol;&lol;&lol;&lol;\">]>"
                    +"<Invoice"+UBL_NS+">"+ublObject("application/pdf","x.pdf",lines(PDF))+"&lol2;</Invoice>";
            for(String hostile:new String[]{fileEntity,externalDtd,parameterEntity,laughs})
                assertTrue(EmbeddedPdf.fromXml(hostile.getBytes(StandardCharsets.UTF_8)).isEmpty(),hostile);
            assertThrows(SocketTimeoutException.class,server::accept,"the parser never connected to the DTD URL");
        } finally {
            Files.deleteIfExists(secret);
        }
    }

    @Test void brokenInputIsEmpty() {
        assertTrue(EmbeddedPdf.fromXml(ubl(ublObject("application/pdf","x.pdf","!!!not base64!!!"))).isEmpty());
        assertTrue(EmbeddedPdf.fromXml(ubl(ublObject("application/pdf","x.pdf",lines("GIF89a....".getBytes(StandardCharsets.US_ASCII))))).isEmpty());
        assertTrue(EmbeddedPdf.fromXml(ubl(ublObject("application/pdf","x.pdf",""))).isEmpty());
        assertTrue(EmbeddedPdf.fromXml("<Invoice><unclosed>".getBytes(StandardCharsets.UTF_8)).isEmpty());
        assertTrue(EmbeddedPdf.fromXml(PDF).isEmpty(),"a PDF is no XML");
        assertTrue(EmbeddedPdf.fromXml(new byte[0]).isEmpty());
        assertTrue(EmbeddedPdf.fromXml(null).isEmpty());
        assertTrue(EmbeddedPdf.fromXml(ubl()).isEmpty(),"no embedded document");
    }

    @Test void inputAbove15MegabytesIsEmpty() {
        byte[] head=ubl(ublObject("application/pdf","x.pdf",lines(PDF)));
        byte[] big=Arrays.copyOf(head,EmbeddedPdf.MAX_BYTES+1);
        Arrays.fill(big,head.length,big.length,(byte)' ');
        assertTrue(EmbeddedPdf.fromXml(big).isEmpty());
        byte[] limit=Arrays.copyOf(head,EmbeddedPdf.MAX_BYTES);
        Arrays.fill(limit,head.length,limit.length,(byte)' ');
        assertTrue(EmbeddedPdf.fromXml(limit).isPresent(),"exactly 15 MB is still read");
    }

    @Test void looksLikeXmlByNameTypeOrFirstBytes() {
        assertTrue(EmbeddedPdf.looksLikeXml("BILL_2026.XML",null,null));
        assertTrue(EmbeddedPdf.looksLikeXml(null,"application/xml",null));
        assertTrue(EmbeddedPdf.looksLikeXml("bestand","text/xml; charset=utf-8",null));
        assertTrue(EmbeddedPdf.looksLikeXml(null,null,"﻿ \r\n<?xml version=\"1.0\"?>".getBytes(StandardCharsets.UTF_8)));
        assertTrue(EmbeddedPdf.looksLikeXml("download","application/octet-stream","  <Invoice/>".getBytes(StandardCharsets.UTF_8)));
        assertTrue(EmbeddedPdf.looksLikeXml(null,null,"<a/>".getBytes(StandardCharsets.UTF_16)),"UTF-16 with its byte order mark");
        assertFalse(EmbeddedPdf.looksLikeXml("factuur.pdf","application/pdf",PDF));
        assertFalse(EmbeddedPdf.looksLikeXml("foto.png","image/png",new byte[]{(byte)0x89,'P','N','G'}));
        assertFalse(EmbeddedPdf.looksLikeXml(null,null,null));
        assertFalse(EmbeddedPdf.looksLikeXml(null,null,"   ".getBytes(StandardCharsets.UTF_8)));
    }
}
