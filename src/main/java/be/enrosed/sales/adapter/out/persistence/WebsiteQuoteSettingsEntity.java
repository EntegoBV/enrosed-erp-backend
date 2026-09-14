package be.enrosed.sales.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Public quote presentation settings; ERP price calculations remain independent. */
@Entity
@Table(name = "website_quote_settings")
public class WebsiteQuoteSettingsEntity {
    @Id
    public Long id = 1L;

    @Version
    @Column(name = "row_revision", nullable = false)
    public long rowRevision;

    @Column(name = "prices_visible", nullable = false)
    public boolean pricesVisible = true;
}
