package be.enrosed.finance.odoo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Odoo 19 JSON-2 with explicit read and separately gated outbox allowlists. No redirects or secret logging. */
@ApplicationScoped
public class OdooClient {
    private static final Set<String> MODELS=Set.of("res.company","res.currency","account.journal",
            "res.partner.bank","account.bank.statement.line","account.move","ir.attachment","res.partner","res.country",
            "account.tax","account.account","account.move.line","account.move.send.wizard","account_edi_proxy_client.user");
    private static final int MAX_RESPONSE_BYTES=24*1024*1024;
    private final OdooConfiguration config;
    private final ObjectMapper json;
    private final HttpClient http;
    @Inject OdooOutboundConfiguration outbound;
    @Inject public OdooClient(OdooConfiguration config,ObjectMapper json) {
        this(config,json,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }
    OdooClient(OdooConfiguration config,ObjectMapper json,HttpClient http) { this.config=config;this.json=json;this.http=http; }
    JsonNode call(String model,String method,Map<String,Object> arguments) {
        config.requireReady();
        if(!MODELS.contains(model)||!Set.of("search_read","fields_get").contains(method))throw new OdooFailure("INVALID_REQUEST");
        return execute(model,method,arguments);
    }
    JsonNode write(String model,String method,Map<String,Object> arguments) {
        config.requireReady();
        if(outbound==null)throw new OdooFailure("OUTBOUND_DISABLED");
        boolean sending=method.equals("action_post")||model.equals("account.move.send.wizard");
        outbound.requireWrite(sending);
        if(!(model.equals("account.move")&&Set.of("create","action_post").contains(method)
                ||model.equals("account.move.send.wizard")&&Set.of("create","action_send_and_print").contains(method)))
            throw new OdooFailure("INVALID_REQUEST");
        return execute(model,method,arguments);
    }
    private JsonNode execute(String model,String method,Map<String,Object> arguments) {
        try {
            Map<String,Object> body=new LinkedHashMap<>(arguments);
            body.put("context",Map.of("allowed_company_ids",List.of(config.companyId),"lang","en_US","bin_size",false,"active_test",false));
            var request=HttpRequest.newBuilder(URI.create(config.baseUrl+"/json/2/"+model+"/"+method))
                    .timeout(Duration.ofSeconds(30)).header("Authorization","Bearer "+config.apiKey)
                    .header("X-Odoo-Database",config.database).header("Content-Type","application/json")
                    .header("Accept","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
            HttpResponse<byte[]> response=http.send(request,ignored->new BoundedBody());
                if(response.statusCode()!=200)throw new OdooFailure(switch(response.statusCode()) {
                    case 401->"AUTH_FAILED";case 403->"ACCESS_DENIED";case 404->"JSON2_UNAVAILABLE";
                    case 400,422->"UNSUPPORTED_SCHEMA";case 429->"RATE_LIMITED";default->"UNAVAILABLE";
                });
                byte[] bytes=response.body();
                if(bytes.length>MAX_RESPONSE_BYTES)throw new OdooFailure("INVALID_RESPONSE");
                JsonNode result=json.readTree(bytes);
                if(result==null||result.has("error")||result.has("traceback"))throw new OdooFailure("INVALID_RESPONSE");
                return result;
        } catch(OdooFailure failure) { throw failure; }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw new OdooFailure("UNAVAILABLE"); }
        catch(Exception unavailable) { throw new OdooFailure("UNAVAILABLE"); }
    }
    /** Consume within HttpClient's request deadline, cancelling before an unbounded body can fill memory. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long size;
        private boolean failed;
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription=subscription;delegate.onSubscribe(subscription); }
        public void onNext(List<ByteBuffer> chunks) {
            if(failed)return;
            for(ByteBuffer chunk:chunks)size+=chunk.remaining();
            if(size>MAX_RESPONSE_BYTES) { failed=true;subscription.cancel();delegate.onError(new OdooFailure("INVALID_RESPONSE")); }
            else delegate.onNext(chunks);
        }
        public void onError(Throwable error) { if(!failed)delegate.onError(error); }
        public void onComplete() { if(!failed)delegate.onComplete(); }
    }
    JsonNode search(String model,List<?> domain,List<String> fields,int limit,String order) {
        JsonNode value=call(model,"search_read",Map.of("domain",domain,"fields",fields,"limit",limit,"order",order));
        if(!value.isArray()||value.size()>limit)throw new OdooFailure("INVALID_RESPONSE");
        return value;
    }
    Set<String> fields(String model) {
        JsonNode value=call(model,"fields_get",Map.of("attributes",List.of("type")));
        if(!value.isObject())throw new OdooFailure("INVALID_RESPONSE");
        Set<String> fields=new HashSet<>();value.fieldNames().forEachRemaining(fields::add);return Set.copyOf(fields);
    }
}
