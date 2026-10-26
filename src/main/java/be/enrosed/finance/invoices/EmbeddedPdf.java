package be.enrosed.finance.invoices;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.Locale;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * The PDF a Peppol (UBL or CII) XML carries inside it, so the finder can preview an XML-only invoice.
 * The parser refuses DOCTYPEs and every external entity, DTD or XInclude: an XML from Odoo is untrusted input.
 */
public final class EmbeddedPdf {
    static final int MAX_BYTES=15*1024*1024;
    private static final byte[] PDF_MAGIC="%PDF-".getBytes(StandardCharsets.US_ASCII);

    private EmbeddedPdf() {}

    public record Extracted(byte[] pdf, String filename /* sanitised, ends with .pdf, may be null */) {}

    /** The first embedded PDF of a UBL or CII document; empty when there is none or the XML is unsafe or broken. */
    public static Optional<Extracted> fromXml(byte[] xml) {
        if(xml==null||xml.length==0||xml.length>MAX_BYTES)return Optional.empty();
        try {
            DocumentBuilder builder=factory().newDocumentBuilder();
            builder.setEntityResolver((publicId,systemId)->{ throw new SAXException("External entities are not read"); });
            builder.setErrorHandler(SILENT);
            Node root=builder.parse(new ByteArrayInputStream(xml)).getDocumentElement();
            // Document order, iteratively (a deeply nested file must not overflow the stack).
            Deque<Node> stack=new ArrayDeque<>();
            if(root!=null)stack.push(root);
            while(!stack.isEmpty()) {
                Node node=stack.pop();
                if(node instanceof Element element&&isPdfObject(element)) {
                    Optional<Extracted> found=extract(element);
                    if(found.isPresent())return found;
                }
                Deque<Node> children=new ArrayDeque<>();
                for(Node child=node.getFirstChild();child!=null;child=child.getNextSibling())
                    if(child.getNodeType()==Node.ELEMENT_NODE)children.push(child);
                while(!children.isEmpty())stack.push(children.pop());
            }
            return Optional.empty();
        } catch(Exception | StackOverflowError broken) {
            return Optional.empty();
        }
    }

    public static boolean looksLikeXml(String filename, String contentType, byte[] head /* first bytes, may be null */) {
        if(filename!=null&&filename.strip().toLowerCase(Locale.ROOT).endsWith(".xml"))return true;
        if(contentType!=null&&contentType.toLowerCase(Locale.ROOT).contains("xml"))return true;
        if(head==null)return false;
        for(byte value:head) {
            int b=value&0xff;
            // UTF-8 / UTF-16 byte order marks, the zero bytes of UTF-16 and leading whitespace.
            if(b==0xEF||b==0xBB||b==0xBF||b==0xFE||b==0xFF||b==0x00||b==' '||b=='\t'||b=='\r'||b=='\n')continue;
            return b=='<';
        }
        return false;
    }

    private static DocumentBuilderFactory factory() throws Exception {
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setValidating(false);
        try {
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        } catch(IllegalArgumentException unsupported) {
            // Not every parser knows these attributes; the features above already refuse external access.
        }
        return factory;
    }

    private static boolean isPdfObject(Element element) {
        String name=element.getLocalName()!=null?element.getLocalName():element.getNodeName();
        int colon=name.indexOf(':');
        if(colon>=0)name=name.substring(colon+1);
        if(!name.equals("EmbeddedDocumentBinaryObject")&&!name.equals("AttachmentBinaryObject"))return false;
        return "application/pdf".equalsIgnoreCase(element.getAttribute("mimeCode").strip());
    }

    private static Optional<Extracted> extract(Element element) {
        String text=element.getTextContent();
        if(text==null)return Optional.empty();
        StringBuilder compact=new StringBuilder(text.length());
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(!Character.isWhitespace(c))compact.append(c);
        }
        if(compact.isEmpty()||compact.length()>(MAX_BYTES+2L)/3*4)return Optional.empty();
        byte[] pdf;
        try { pdf=Base64.getDecoder().decode(compact.toString()); }
        catch(IllegalArgumentException invalid) { return Optional.empty(); }
        if(pdf.length>MAX_BYTES||pdf.length<PDF_MAGIC.length
                ||!Arrays.equals(Arrays.copyOf(pdf,PDF_MAGIC.length),PDF_MAGIC))return Optional.empty();
        return Optional.of(new Extracted(pdf,filename(element.getAttribute("filename"))));
    }

    static String filename(String raw) {
        if(raw==null)return null;
        String safe=raw.strip().replaceAll("[^A-Za-z0-9._ -]","_");
        if(safe.length()>200)safe=safe.substring(0,200);
        if(safe.isBlank()||safe.replace("_","").replace(".","").isBlank())return null;
        if(!safe.toLowerCase(Locale.ROOT).endsWith(".pdf"))safe=safe+".pdf";
        return safe;
    }

    private static final ErrorHandler SILENT=new ErrorHandler() {
        @Override public void warning(SAXParseException exception) {}
        @Override public void error(SAXParseException exception) throws SAXException { throw exception; }
        @Override public void fatalError(SAXParseException exception) throws SAXException { throw exception; }
    };
}
