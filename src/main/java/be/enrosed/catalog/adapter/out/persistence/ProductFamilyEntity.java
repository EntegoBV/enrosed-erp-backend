package be.enrosed.catalog.adapter.out.persistence;

import be.enrosed.catalog.domain.PublicationState;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Customer-facing model shared by its stock-bearing colour variants. */
@Entity
@Table(name = "product_family")
public class ProductFamilyEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, unique = true)
    public String familyKey;
    @Column(unique = true)
    public String publicHandle;
    public boolean active = true;

    public String name;
    @Column(length = 2000) public String summary;
    @Column(length = 10000) public String description;
    public String format;
    @Column(length = 10000) public String highlightsJson;

    public Long categoryId;
    public String categoryKey;
    public String categoryName;
    public int categoryPosition;
    public String collectionKey;
    public int productPosition;
    /** Optional active member selected for family cards; null means the normal/base member. */
    public Long cardFeaturedProductId;
    @Column(length = 10000) public String tagsJson;

    @Enumerated(EnumType.STRING)
    public PublicationState websiteStatus = PublicationState.DRAFT;
    @Enumerated(EnumType.STRING)
    public PublicationState orderAppStatus = PublicationState.DRAFT;
    @Enumerated(EnumType.STRING)
    public PublicationState catalogueStatus = PublicationState.DRAFT;

    public String seoTitle;
    @Column(length = 2000) public String seoDescription;

    public BigDecimal dimensionLength;
    public BigDecimal dimensionWidth;
    public BigDecimal dimensionHeight;
    public String dimensionUnit;
    @Column(length = 1000) public String dimensionRaw;

    public Instant createdAt;
    public Instant updatedAt;
    public String lastImportKey;

    @OneToMany(mappedBy = "family", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    @OrderBy("language ASC")
    public List<ProductFamilyTextEntity> texts = new ArrayList<>();

    @OneToMany(mappedBy = "family", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    @OrderBy("position ASC")
    public List<ProductFamilyPhotoEntity> photos = new ArrayList<>();

    @OneToMany(mappedBy = "family", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    @OrderBy("position ASC")
    public List<ProductPackageEntity> packages = new ArrayList<>();

    @OneToMany(mappedBy = "family", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    @OrderBy("position ASC")
    public List<ProductFamilyCollectionEntity> collections = new ArrayList<>();
}
