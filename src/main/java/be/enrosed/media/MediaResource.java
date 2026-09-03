package be.enrosed.media;

import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;

@Path("/api/media-assets")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class MediaResource {
    private final MediaService media;

    public MediaResource(MediaService media) {
        this.media = media;
    }

    @GET
    public List<MediaDtos.Summary> list(
            @QueryParam("q") String query,
            @QueryParam("kind") MediaKind kind,
            @QueryParam("role") MediaRole role,
            @QueryParam("archived") Boolean archived,
            @QueryParam("targetType") MediaTargetType targetType,
            @QueryParam("targetId") Long targetId,
            @QueryParam("includeArchived") @DefaultValue("false") boolean includeArchived,
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("limit") @DefaultValue("100") int limit,
            @QueryParam("folder") String folder,
            @QueryParam("linked") Boolean linked) {
        boolean rootOnly = "root".equalsIgnoreCase(folder);
        Long folderId = folder == null || folder.isBlank() || rootOnly ? null : Long.valueOf(folder);
        return media.list(query, kind, role, archived, targetType, targetId,
                includeArchived, offset, limit, folderId, rootOnly, linked);
    }

    @PUT
    @Path("/{id}/folder")
    public MediaDtos.Detail move(@PathParam("id") long id, MediaDtos.MoveRequest request) {
        return media.move(id, request == null ? null : request.folderId());
    }

    @POST
    @Path("/{id}/share")
    public MediaDtos.Detail share(@PathParam("id") long id) {
        return media.share(id);
    }

    @DELETE
    @Path("/{id}/share")
    public MediaDtos.Detail unshare(@PathParam("id") long id) {
        return media.unshare(id);
    }

    @GET
    @Path("/{id}")
    public MediaDtos.Detail get(@PathParam("id") long id) {
        return media.get(id);
    }

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response upload(@RestForm("file") FileUpload upload,
                           @RestForm("name") String name,
                           @RestForm("folderId") Long folderId) throws IOException {
        MediaUploadPolicy.ValidatedFile file = validated(upload);
        MediaDtos.UploadResult result = media.upload(name, file, folderId);
        return Response.status(result.reused() ? Response.Status.OK : Response.Status.CREATED)
                .entity(result).build();
    }

    @PUT
    @Path("/{id}/metadata")
    public MediaDtos.Detail rename(@PathParam("id") long id, MediaDtos.MetadataRequest request) {
        if (request == null) throw new BusinessRuleException("Geef een naam op");
        return media.rename(id, request.name());
    }

    @POST
    @Path("/{id}/versions")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public MediaDtos.Detail replace(@PathParam("id") long id,
                                    @RestForm("file") FileUpload upload) throws IOException {
        return media.replace(id, validated(upload));
    }

    @POST
    @Path("/{id}/links")
    public MediaDtos.Detail link(@PathParam("id") long id, MediaDtos.LinkRequest request) {
        return media.link(id, request);
    }

    @DELETE
    @Path("/{id}/links/{linkId}")
    public MediaDtos.Detail unlink(@PathParam("id") long id, @PathParam("linkId") long linkId) {
        return media.unlink(id, linkId);
    }

    @POST
    @Path("/{id}/archive")
    public MediaDtos.Detail archive(@PathParam("id") long id) {
        return media.archive(id);
    }

    @POST
    @Path("/{id}/restore")
    public MediaDtos.Detail restore(@PathParam("id") long id) {
        return media.restore(id);
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") long id) {
        media.delete(id);
        return Response.noContent().build();
    }

    @GET
    @Path("/{id}/file")
    @Produces(MediaType.WILDCARD)
    public Response file(@PathParam("id") long id, @QueryParam("variant") String variant) {
        MediaService.FileRef file = "web".equals(variant) ? media.webFile(id) : media.file(id);
        return fileResponse(file, MediaUploadPolicy.safeInline(file.contentType()));
    }

    @GET
    @Path("/{id}/download")
    @Produces(MediaType.WILDCARD)
    public Response download(@PathParam("id") long id, @QueryParam("variant") String variant) {
        return fileResponse("web".equals(variant) ? media.webFile(id) : media.file(id), false);
    }

    @GET
    @Path("/{id}/thumbnail")
    @Produces(MediaType.WILDCARD)
    public Response thumbnail(@PathParam("id") long id) {
        MediaService.FileRef file = media.thumbnail(id);
        return Response.ok(file.data()).type(file.contentType())
                .header("Content-Length", file.sizeBytes())
                .header("Content-Disposition", "inline")
                .header("X-Content-Type-Options", "nosniff")
                // The route is asset-stable while its current version is not. Never let a browser
                // reuse an old rendition after version replacement or rollback.
                .header("Cache-Control", "private, no-store")
                .build();
    }

    @GET
    @Path("/{id}/versions/{versionId}/file")
    @Produces(MediaType.WILDCARD)
    public Response versionFile(@PathParam("id") long id,
                                @PathParam("versionId") long versionId) {
        MediaService.FileRef file = media.versionFile(id, versionId);
        return fileResponse(file, MediaUploadPolicy.safeInline(file.contentType()));
    }

    private static MediaUploadPolicy.ValidatedFile validated(FileUpload upload) throws IOException {
        if (upload == null) throw new BusinessRuleException("Geen bestand meegestuurd");
        try (InputStream input = Files.newInputStream(upload.uploadedFile())) {
            return MediaUploadPolicy.validate(upload.fileName(), upload.contentType(), input);
        }
    }

    private static Response fileResponse(MediaService.FileRef file, boolean inline) {
        String disposition = (inline ? "inline" : "attachment") + "; filename=\""
                + MediaUploadPolicy.contentDispositionFilename(file.originalFilename()) + "\"";
        return Response.ok(file.data()).type(file.contentType())
                .header("Content-Length", file.sizeBytes())
                .header("Content-Disposition", disposition)
                .header("X-Content-Type-Options", "nosniff")
                .header("Cache-Control", "private, no-store")
                .build();
    }
}
