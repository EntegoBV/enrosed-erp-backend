package be.enrosed.planning;

import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.security.AdminIdentityProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/** The dashboard's agenda and task list: small, fast, ours. */
@Path("/api/planner")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(AdminIdentityProvider.ADMIN_ROLE)
public class PlannerResource {

    @jakarta.inject.Inject
    be.enrosed.push.WebPushNotifier phones;

    /** Files ride the same store as product photos: one backup, one place. */
    @jakarta.inject.Inject
    jakarta.enterprise.inject.Instance<be.enrosed.catalog.application.port.out.PhotoStorage> photoStorage;

    public record Attachment(Long id, String filename, String contentType, long sizeBytes) {
        static Attachment from(PlannerAttachmentEntity entity) {
            return new Attachment(entity.id, entity.filename, entity.contentType, entity.sizeBytes);
        }
    }

    public record PlannerItem(Long id, PlannerItemEntity.Kind kind, String title,
                              LocalDate onDate, String atTime, String note, boolean done,
                              Boolean pinned, Long parentId, List<Attachment> attachments) {
        static PlannerItem from(PlannerItemEntity entity, List<Attachment> attachments) {
            return new PlannerItem(entity.id, entity.kind, entity.title,
                    entity.onDate, entity.atTime, entity.note, entity.done, entity.pinned,
                    entity.parentId, attachments);
        }
    }

    private static List<Attachment> attachmentsOf(Long itemId) {
        return PlannerAttachmentEntity.<PlannerAttachmentEntity>list("itemId = ?1 order by addedAt", itemId)
                .stream().map(Attachment::from).toList();
    }

    @GET
    public List<PlannerItem> list() {
        return PlannerItemEntity.<PlannerItemEntity>listAll().stream()
                .sorted(Comparator
                        .comparing((PlannerItemEntity item) -> item.onDate,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(item -> item.atTime, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(item -> item.createdAt))
                .map(entity -> PlannerItem.from(entity, attachmentsOf(entity.id)))
                .toList();
    }

    @POST
    @Transactional
    public Response create(PlannerItem request) {
        PlannerItemEntity entity = new PlannerItemEntity();
        apply(entity, request);
        entity.createdAt = Instant.now();
        entity.persist();
        String when = entity.onDate == null ? ""
                : " op " + be.enrosed.shared.DocumentFormat.be(entity.onDate)
                + (entity.atTime == null || entity.atTime.isBlank() ? "" : " om " + entity.atTime);
        if (phones != null) phones.notifyAll("agenda", "\uD83D\uDCC5 In de agenda gezet", entity.title + when, "/");
        return Response.status(Response.Status.CREATED).entity(PlannerItem.from(entity, List.of())).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public PlannerItem update(@PathParam("id") long id, PlannerItem request) {
        PlannerItemEntity entity = PlannerItemEntity.findById(id);
        if (entity == null) throw new NotFoundException("Agendapunt", id);
        apply(entity, request);
        return PlannerItem.from(entity, attachmentsOf(entity.id));
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") long id) {
        if (!PlannerItemEntity.deleteById(id)) throw new NotFoundException("Agendapunt", id);
        /* Tasks under the appointment stay alive as planned tasks of their own. */
        PlannerItemEntity.update("parentId = null where parentId = ?1", id);
        for (PlannerAttachmentEntity attachment
                : PlannerAttachmentEntity.<PlannerAttachmentEntity>list("itemId = ?1", id)) {
            try { photoStorage.get().delete(attachment.storageKey); } catch (RuntimeException ignored) { }
            attachment.delete();
        }
        return Response.noContent().build();
    }

    /* ---- attachments: the quote or floor plan stays with the appointment ---- */

    @POST
    @Path("/{id}/attachments")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Transactional
    public Attachment upload(@PathParam("id") long id,
                             @org.jboss.resteasy.reactive.RestForm("file") org.jboss.resteasy.reactive.multipart.FileUpload file)
            throws java.io.IOException {
        if (PlannerItemEntity.findById(id) == null) throw new NotFoundException("Agendapunt", id);
        if (file == null) throw new BusinessRuleException("Geen bestand meegestuurd");
        byte[] bytes = java.nio.file.Files.readAllBytes(file.uploadedFile());
        if (bytes.length == 0) throw new BusinessRuleException("Het bestand is leeg");
        if (bytes.length > 25 * 1024 * 1024) throw new BusinessRuleException("Een bestand mag hoogstens 25 MB zijn");
        String name = file.fileName() == null || file.fileName().isBlank() ? "bijlage" : file.fileName();
        String type = file.contentType() == null || file.contentType().isBlank()
                ? "application/octet-stream" : file.contentType();
        var stored = photoStorage.get().store(name, type, bytes);
        PlannerAttachmentEntity entity = new PlannerAttachmentEntity();
        entity.itemId = id;
        entity.filename = name;
        entity.contentType = type;
        entity.sizeBytes = bytes.length;
        entity.storageKey = stored.storageKey();
        entity.addedAt = java.time.Instant.now();
        entity.persist();
        return Attachment.from(entity);
    }

    @GET
    @Path("/{id}/attachments/{attachmentId}/file")
    @Produces(MediaType.WILDCARD)
    public Response attachmentFile(@PathParam("id") long id, @PathParam("attachmentId") long attachmentId) {
        PlannerAttachmentEntity entity = PlannerAttachmentEntity.findById(attachmentId);
        if (entity == null || !entity.itemId.equals(id)) throw new NotFoundException("Bijlage", attachmentId);
        return Response.ok(photoStorage.get().read(entity.storageKey))
                .type(entity.contentType)
                .header("Content-Disposition", "attachment; filename=\"" + entity.filename.replace("\"", "'") + "\"")
                .build();
    }

    @DELETE
    @Path("/{id}/attachments/{attachmentId}")
    @Transactional
    public Response deleteAttachment(@PathParam("id") long id, @PathParam("attachmentId") long attachmentId) {
        PlannerAttachmentEntity entity = PlannerAttachmentEntity.findById(attachmentId);
        if (entity == null || !entity.itemId.equals(id)) throw new NotFoundException("Bijlage", attachmentId);
        try { photoStorage.get().delete(entity.storageKey); } catch (RuntimeException ignored) { }
        entity.delete();
        return Response.noContent().build();
    }

    private static void apply(PlannerItemEntity entity, PlannerItem request) {
        if (request == null || request.title() == null || request.title().isBlank()) {
            throw new BusinessRuleException("Geef een omschrijving op");
        }
        entity.kind = request.kind() == null ? PlannerItemEntity.Kind.TASK : request.kind();
        entity.title = request.title().strip();
        entity.onDate = request.onDate();
        entity.atTime = request.atTime() == null || request.atTime().isBlank() ? null : request.atTime().strip();
        entity.note = request.note() == null || request.note().isBlank() ? null : request.note().strip();
        entity.done = request.done();
        entity.pinned = request.pinned() != null && request.pinned();
        entity.parentId = request.parentId();
    }
}
