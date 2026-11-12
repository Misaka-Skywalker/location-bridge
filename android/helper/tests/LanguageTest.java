package org.locationbridge.source;

/** Checks the translated aviation labels and Chinese fallback without an Android runtime. */
public final class LanguageTest {
    public static void main(String[] args) throws Exception {
        require("纬度".equals(LanguageCatalog.translate("纬度", false)));
        require("LAT".equals(LanguageCatalog.translate("纬度", true)));
        require("LON".equals(LanguageCatalog.translate("经度", true)));
        require("TRK TRUE".equals(LanguageCatalog.translate("真航迹角", true)));
        require("TRK MAG".equals(LanguageCatalog.translate("磁航迹角", true)));
        require("VAR (estimated)".equals(LanguageCatalog.translate("磁差（估算）", true)));
        require("unmapped".equals(LanguageCatalog.translate("unmapped", true)));
        System.out.println("LanguageTest passed");
    }
    private static void require(boolean value) { if (!value) throw new AssertionError(); }
}
