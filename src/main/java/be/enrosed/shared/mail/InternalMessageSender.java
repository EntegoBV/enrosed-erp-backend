package be.enrosed.shared.mail;

import java.util.List;

/** Outbound internal message transport shared by business modules. */
public interface InternalMessageSender {

    /** Plain text to the internal recipient; the historical route for portal actions. */
    void sendInternal(String subject, String body);

    /** One labelled fact on a team notice, e.g. "Bedrijf" · "Royal Garden". */
    record TeamFact(String label, String value) {}

    /** One requested line on a team notice: what, how many, and a remark such as the carton count. */
    record TeamLine(String description, String quantity, String note) {}

    /**
     * A styled notice for the team mailbox: what came in, from whom, what
     * they asked, and where to act. The text fallback carries the same
     * facts for clients that cannot show HTML.
     */
    record TeamNotice(String subject, String kicker, String title, String intro,
                      List<TeamFact> facts, List<TeamLine> lines,
                      String messageTitle, String message,
                      String buttonLabel, String buttonUrl,
                      String secondaryLabel, String secondaryUrl,
                      String textFallback) {}

    /** Delivers a team notice; throws when it could not leave so a durable caller may retry. */
    void sendTeamNotice(TeamNotice notice);
}
