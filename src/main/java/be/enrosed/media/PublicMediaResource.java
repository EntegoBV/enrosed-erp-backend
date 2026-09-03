package be.enrosed.media;

import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.nio.charset.StandardCharsets;

/**
 * Files behind a public link: no login, only the token. The link always
 * serves the current version, and stops the moment it is revoked.
 */
@Path("/api/public/media")
@PermitAll
public class PublicMediaResource {
    private final MediaService media;

    public PublicMediaResource(MediaService media) {
        this.media = media;
    }

    @GET
    @Path("/{token}")
    @Produces(MediaType.WILDCARD)
    public Response view(@PathParam("token") String token) {
        MediaService.FileRef file = media.publicFile(token);
        return response(file, MediaUploadPolicy.safeInline(file.contentType()));
    }

    /** The lighter web copy of an image; a document comes as itself. */
    @GET
    @Path("/{token}/web")
    @Produces(MediaType.WILDCARD)
    public Response web(@PathParam("token") String token) {
        MediaService.FileRef file = media.publicFile(token, true);
        return response(file, MediaUploadPolicy.safeInline(file.contentType()));
    }

    @GET
    @Path("/{token}/download")
    @Produces(MediaType.WILDCARD)
    public Response download(@PathParam("token") String token) {
        return response(media.publicFile(token), false);
    }

    private static Response response(MediaService.FileRef file, boolean inline) {
        String filename = file.originalFilename() == null ? "bestand" : file.originalFilename();
        String ascii = filename.replaceAll("[^A-Za-z0-9._-]", "_");
        String encoded = java.net.URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return Response.ok(file.data()).type(file.contentType())
                .header("Content-Length", file.sizeBytes())
                .header("Content-Disposition", (inline ? "inline" : "attachment")
                        + "; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded)
                .header("X-Content-Type-Options", "nosniff")
                .header("Cache-Control", "private, no-store")
                .build();
    }
}
