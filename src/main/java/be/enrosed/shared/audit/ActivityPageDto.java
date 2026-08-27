package be.enrosed.shared.audit;

import java.util.List;

/** Cursor page; nextBefore is the last returned event id, or null at the end. */
public record ActivityPageDto(List<ActivityDto> items, Long nextBefore) {}
