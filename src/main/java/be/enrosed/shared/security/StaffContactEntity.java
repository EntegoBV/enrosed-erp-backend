package be.enrosed.shared.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Contact details belong to a configured staff identity, never to its credentials. */
@Entity
@Table(name = "staff_contact")
public class StaffContactEntity {
    @Id
    @Column(length = 64, nullable = false)
    public String username;

    @Column(length = 50)
    public String phone;
}
