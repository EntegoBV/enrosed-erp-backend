package be.enrosed.publicform;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Pre-seeded lock stripes avoid one global Java monitor and work across replicas. */
@Entity
@Table(name = "public_form_lock")
public class PublicFormLockEntity extends PanacheEntityBase {
    public static final int STRIPES = 64;

    @Id
    public int id;

    public PublicFormLockEntity() {}

    PublicFormLockEntity(int id) {
        this.id = id;
    }
}
