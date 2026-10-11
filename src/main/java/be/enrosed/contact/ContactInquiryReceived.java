package be.enrosed.contact;

/** Carries only the saved inquiry id; customer details stay off phone lock screens. */
public record ContactInquiryReceived(long id) {}
