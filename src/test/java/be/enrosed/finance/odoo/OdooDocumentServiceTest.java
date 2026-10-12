package be.enrosed.finance.odoo;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OdooDocumentServiceTest {
    final ObjectMapper json=new ObjectMapper();
    OdooDocumentService service() { var service=new OdooDocumentService();service.config=OdooClientTest.config();service.client=mock(OdooClient.class);return service; }
    @Test void attachmentCannotBeDownloadedUnlessInvoiceBelongsToConfiguredCompany() throws Exception {
        var service=service();when(service.client.search(eq("account.move"),anyList(),anyList(),eq(1),anyString())).thenReturn(json.readTree("[]"));
        assertEquals("NOT_FOUND",assertThrows(OdooFailure.class,()->service.download(10,20)).code);
        verify(service.client,never()).search(eq("ir.attachment"),anyList(),anyList(),anyInt(),anyString());
    }
    @Test void oversizedAttachmentsAreRejectedBeforeLoadingBinaryData() throws Exception {
        var service=service();when(service.client.search(eq("account.move"),anyList(),anyList(),eq(1),anyString())).thenReturn(json.readTree("[{\"id\":10}]"));
        when(service.client.search(eq("ir.attachment"),anyList(),anyList(),eq(1),anyString())).thenReturn(json.readTree("[{\"id\":20,\"res_id\":10,\"name\":\"invoice.pdf\",\"mimetype\":\"application/pdf\",\"file_size\":16777216,\"type\":\"binary\"}]"));
        assertEquals("ATTACHMENT_TOO_LARGE",assertThrows(OdooFailure.class,()->service.download(10,20)).code);
        verify(service.client,times(1)).search(eq("ir.attachment"),anyList(),anyList(),anyInt(),anyString());
    }
    @Test void fieldAttachmentsAreIncludedAndDownloadedBytesAreTheStoredFile() throws Exception {
        var service=service();when(service.client.search(eq("account.move"),anyList(),anyList(),eq(1),anyString())).thenReturn(json.readTree("[{\"id\":10}]"));
        when(service.client.search(eq("ir.attachment"),anyList(),anyList(),eq(1),anyString())).thenAnswer(call->{
            List<?> domain=call.getArgument(1);assertTrue(domain.toString().contains("res_field"));assertTrue(domain.toString().contains("[res_id, in, [10]]"));
            List<?> fields=call.getArgument(2);
            return fields.contains("datas")?json.readTree("[{\"id\":20,\"res_id\":10,\"datas\":\"PHhtbC8+\"}]"):
                    json.readTree("[{\"id\":20,\"res_id\":10,\"name\":\"invoice.xml\",\"mimetype\":\"application/xml\",\"file_size\":6,\"type\":\"binary\"}]");
        });
        var download=service.download(10,20);assertEquals("<xml/>",new String(download.bytes(),java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("application/xml",download.mimeType());
    }
}
