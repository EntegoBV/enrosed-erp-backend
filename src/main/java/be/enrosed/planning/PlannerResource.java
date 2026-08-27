package be.enrosed.planning;

import be.enrosed.shared.BusinessRuleException;
import be.enrosed.shared.NotFoundException;
import be.enrosed.shared.audit.ActivityLogService;
import be.enrosed.push.StaffActionPushNotifier;
import be.enrosed.shared.security.ActorRef;
import be.enrosed.shared.security.AdminIdentityProvider;
import be.enrosed.shared.security.CurrentActor;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
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

    private static final String ACTIVITY_ENTITY = "PLANNER_ITEM";

    @Inject
    Instance<ActivityLogService> activity;

    @Inject
    Instance<CurrentActor> actor;

    @Inject
    Event<StaffActionPushNotifier.Ready> staffPush;

    @Inject
    Event<PlannerAttachmentCleanup.DeleteReady> attachmentDeleteCleanup;

    @Inject
    Event<PlannerAttachmentCleanup.UploadReady> attachmentUploadCleanup;

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
        recordActivity(ActivityLogService.ACTION_CREATED, entity, "Agendapunt aangemaakt");
        if (staffPush != null && entity.id != null) {
            staffPush.fire(StaffActionPushNotifier.Ready.plannerCreated(
                    entity.id, entity.onDate, entity.atTime, currentActor()));
        }
        return Response.status(Response.Status.CREATED).entity(PlannerItem.from(entity, List.of())).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    public PlannerItem update(@PathParam("id") long id, PlannerItem request) {
        PlannerItemEntity entity = PlannerItemEntity.findById(id);
        if (entity == null) throw new NotFoundException("Agendapunt", id);
        boolean wasDone = entity.done;
        apply(entity, request);
        if (wasDone != entity.done) {
            recordActivity(ActivityLogService.ACTION_STATUS_CHANGED, entity,
                    entity.done ? "Agendapunt afgerond" : "Agendapunt heropend");
        } else {
            recordActivity(ActivityLogService.ACTION_UPDATED, entity, "Agendapunt bijgewerkt");
        }
        return PlannerItem.from(entity, attachmentsOf(entity.id));
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response delete(@PathParam("id") long id) {
        PlannerItemEntity item = PlannerItemEntity.findById(id);
        if (item == null) throw new NotFoundException("Agendapunt", id);
        item.delete();
        /* Tasks under the appointment stay alive as planned tasks of their own. */
        PlannerItemEntity.update("parentId = null where parentId = ?1", id);
        List<PlannerAttachmentEntity> attachments =
                PlannerAttachmentEntity.<PlannerAttachmentEntity>list("itemId = ?1", id);
        List<String> storageKeys = attachments.stream().map(attachment -> attachment.storageKey).toList();
        for (PlannerAttachmentEntity attachment : attachments) {
            attachment.delete();
        }
        recordActivity(ActivityLogService.ACTION_DELETED, item, "Agendapunt verwijderd");
        fireAttachmentCleanup(storageKeys);
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
        fireAttachmentUploadCleanup(new PlannerAttachmentCleanup.UploadReady(
                id, stored.storageKey()));
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
        String storageKey = entity.storageKey;
        entity.delete();
        fireAttachmentCleanup(List.of(storageKey));
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

    private ActorRef currentActor() {
        return actor != null && actor.isResolvable() ? actor.get().current() : ActorRef.SYSTEM;
    }

    /** Audit joins the planner transaction and never accepts a client-supplied actor. */
    private void recordActivity(String action, PlannerItemEntity item, String summary) {
        if (activity == null || !activity.isResolvable()) return;
        activity.get().record(action, ACTIVITY_ENTITY,
                item.id == null ? null : item.id.toString(), item.title, summary);
    }

    /** Attachment bytes are external state and may only disappear after the DB commit. */
    private void fireAttachmentCleanup(List<String> storageKeys) {
        if (attachmentDeleteCleanup != null && storageKeys != null && !storageKeys.isEmpty()) {
            attachmentDeleteCleanup.fire(new PlannerAttachmentCleanup.DeleteReady(storageKeys));
        }
    }

    /** Compensates an external upload if persisting its attachment row rolls back. */
    private void fireAttachmentUploadCleanup(PlannerAttachmentCleanup.UploadReady ready) {
        if (attachmentUploadCleanup != null) attachmentUploadCleanup.fire(ready);
    }
}
