package org.locationbridge.source;

import java.nio.charset.StandardCharsets;
public final class NmeaTxtTest {
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static byte[] sentence(String body, String suffix) {
        int xor=0; for (byte b : body.getBytes(StandardCharsets.US_ASCII)) xor ^= b;
        return ("$"+body+"*"+String.format(java.util.Locale.ROOT,"%02X",xor)+suffix).getBytes(StandardCharsets.US_ASCII);
    }
    public static void main(String[] args) {
        check("114514".equals(NmeaTxt.parse(sentence("GPTXT,01,01,02,114514","\r\n"))),"GP checksum");
        check("Hello".equals(NmeaTxt.parse(sentence("GNTXT,01,01,00,Hello",""))),"GN no CRLF");
        check(NmeaTxt.parse("$GPTXT,01,01,02,114514*00\r\n".getBytes(StandardCharsets.US_ASCII)) == null,"bad checksum");
        check(NmeaTxt.parse(sentence("GPTXT,02,01,02,first","")) == null,"multipart rejected");
        check(NmeaTxt.parse(sentence("GPTXT,01,02,02,bad","")) == null,"index rejected");
        check(NmeaTxt.parse(sentence("GATXT,01,01,02,bad","")) == null,"talker rejected");
        check(NmeaTxt.parse(sentence("GPTXT,01,01,XX,bad","")) == null,"invalid type");
        check(NmeaTxt.parse(sentence("GPTXT,01,01,02,","")) == null,"empty text");
        check(NmeaTxt.parse(sentence("GPTXT,01,01,02,text","\n")) == null,"bare LF");
        check(NmeaTxt.parse(new byte[]{0,1,2,3}) == null,"binary rejected");
        check(NmeaTxt.parse(sentence("GPTXT,01,01,02,"+"a".repeat(120),"")) == null,"too long");
        System.out.println("NmeaTxt tests passed");
    }
}
