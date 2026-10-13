package be.enrosed.finance.banking;

import jakarta.persistence.*;
import java.time.Instant;

/** Display/source metadata only: archiving an account never removes its financial history. */
@Entity
@Table(name="bank_account",uniqueConstraints=@UniqueConstraint(name="uq_bank_account_odoo_journal",columnNames={"odoo_source_namespace","odoo_journal_id"}))
public class BankAccountEntity {
    @Id @Column(name="account_key",length=120) public String key;
    /** Alias keys share the same primary-key namespace as canonical keys, preventing ambiguous ownership. */
    @Column(name="canonical_key",length=120) public String canonicalKey;
    @Column(nullable=false,length=120) public String label;
    @Column(nullable=false,length=24) public String source;
    @Column(nullable=false) public boolean archived;
    @Column(name="odoo_source_namespace",length=64) public String odooSourceNamespace;
    @Column(name="odoo_company_id") public Long odooCompanyId;
    @Column(name="odoo_journal_id") public Long odooJournalId;
    @Column(name="created_at",nullable=false) public Instant createdAt;
    @Column(name="updated_at",nullable=false) public Instant updatedAt;
}
