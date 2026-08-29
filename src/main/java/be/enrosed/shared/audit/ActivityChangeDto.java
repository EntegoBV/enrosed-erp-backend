package be.enrosed.shared.audit;

/** One safe, human-readable field change attached to an activity event. */
public record ActivityChangeDto(
        String field,
        String label,
        String beforeValue,
        String afterValue
) {}
