package be.enrosed.sourcing.domain;

import java.math.BigDecimal;

public enum ContainerType {
    TWENTY_GP("20GP", "20' Standard", 28),
    FORTY_GP("40GP", "40' Standard", 58),
    FORTY_HQ("40HQ", "40' High Cube", 68),
    LCL("LCL", "Groepage", 0);

    private final String code;
    private final String label;
    private final int cbm;

    ContainerType(String code, String label, int cbm) {
        this.code = code;
        this.label = label;
        this.cbm = cbm;
    }

    public String code() { return code; }
    public String label() { return label; }
    public BigDecimal capacityCbm() { return BigDecimal.valueOf(cbm); }
    public boolean hasCapacity() { return cbm > 0; }

    public static ContainerType fromCode(String code) {
        for (ContainerType type : values()) {
            if (type.code.equalsIgnoreCase(code)) return type;
        }
        return FORTY_HQ;
    }
}
