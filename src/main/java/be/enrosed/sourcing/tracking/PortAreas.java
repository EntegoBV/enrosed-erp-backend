package be.enrosed.sourcing.tracking;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A port and its terminals are one place. The sources name the same call in their own words: the ship's feed says
 * "Europoort" (NLEUR) where the order says "Rotterdam", the bill of lading says "Yantian" where the order says
 * "Shenzhen". Compared by exact name those never matched, so the port calls of a linked ship and the dates of a
 * carrier were refused for the order's own route (2026-11-15: EVER ALLY was in at Europoort, the order still said
 * 30/11).
 *
 * <p>Pure: a name or a UN/LOCODE in, the name of its area out. A text that stands in no area is its own area, so
 * two ports this list does not know still compare by name, as they always did.</p>
 */
public final class PortAreas {
    private static final Map<String, String> AREAS = new HashMap<>();

    static {
        put("ROTTERDAM", "ROTTERDAM", "EUROPOORT", "MAASVLAKTE", "BOTLEK", "PERNIS", "WAALHAVEN", "EEMHAVEN",
                "VLAARDINGEN", "SCHIEDAM", "NLRTM", "NLEUR", "NLMSV", "NLBOT", "NLPER", "NLVLA", "NLSCI");
        put("ANTWERP", "ANTWERP", "ANTWERPEN", "ANVERS", "ANTWERPBRUGES", "BEANR");
        put("ZEEBRUGGE", "ZEEBRUGGE", "BEZEE");
        put("AMSTERDAM", "AMSTERDAM", "NLAMS");
        put("HAMBURG", "HAMBURG", "DEHAM");
        put("BREMERHAVEN", "BREMERHAVEN", "DEBRV");
        put("FELIXSTOWE", "FELIXSTOWE", "GBFXT");
        put("LEHAVRE", "LEHAVRE", "HAVRE", "FRLEH");
        put("NINGBO", "NINGBO", "NINGBOZHOUSHAN", "BEILUN", "ZHOUSHAN", "MEISHAN", "DAXIE", "CHUANSHAN", "ZHENHAI",
                "CNNGB", "CNNBO", "CNZOS");
        put("SHANGHAI", "SHANGHAI", "YANGSHAN", "WAIGAOQIAO", "CNSHA", "CNSGH");
        put("SHENZHEN", "SHENZHEN", "YANTIAN", "SHEKOU", "CHIWAN", "MAWAN", "DACHANBAY", "NANSHAN",
                "CNSZX", "CNYTN", "CNSHK", "CNCWN", "CNMWN", "CNDCB");
        put("GUANGZHOU", "GUANGZHOU", "NANSHA", "HUANGPU", "CNCAN", "CNGZG", "CNNSA", "CNHUA");
        put("QINGDAO", "QINGDAO", "QIANWAN", "CNTAO", "CNQDG");
        put("TIANJIN", "TIANJIN", "XINGANG", "TIANJINXINGANG", "CNTSN", "CNTXG", "CNXGG");
        put("XIAMEN", "XIAMEN", "HAICANG", "CNXMN");
    }

    private PortAreas() {}

    /**
     * The area a port name or UN/LOCODE belongs to; the text itself (normalised) when it stands in no area; ""
     * for nothing. "Europoort", "NLEUR" and "Rotterdam (NL)" all answer "ROTTERDAM".
     */
    public static String area(String nameOrCode) {
        String plain = plain(nameOrCode);
        return AREAS.getOrDefault(plain, plain);
    }

    /** The order's port is the place a source names, by its code or by its name. A blank order port matches nothing. */
    public static boolean same(String configured, String code, String name) {
        String expected = area(configured);
        return !expected.isEmpty() && (expected.equals(area(name)) || expected.equals(area(code)));
    }

    /**
     * How every port text is compared: without what follows the first comma ("NINGBO, CHINA"), without a country in
     * brackets at the end ("ROTTERDAM (NL)"), without accents, upper case, letters and digits only.
     */
    private static String plain(String value) {
        if (value == null) return "";
        String result = value.strip().split(",", 2)[0].replaceFirst("\\s*\\([A-Za-z]{2}\\)\\s*$", "");
        return Normalizer.normalize(result, Normalizer.Form.NFKD).replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static void put(String name, String... members) {
        for (String member : members) AREAS.put(member, name);
    }
}
