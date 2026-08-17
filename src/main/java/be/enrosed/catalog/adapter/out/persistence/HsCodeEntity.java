package be.enrosed.catalog.adapter.out.persistence;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "hs_code")
public class HsCodeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(unique = true)
    public String code;
    public String description;

    @Column(precision = 19, scale = 4)
    public BigDecimal dutyRatePct;
}
