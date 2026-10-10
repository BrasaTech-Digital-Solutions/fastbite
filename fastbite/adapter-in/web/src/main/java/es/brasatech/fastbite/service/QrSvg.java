package es.brasatech.fastbite.service;

import io.nayuki.qrcodegen.QrCode;

/**
 * QR codes as SVG, drawn on the server so a printed invoice never depends on a script or a network.
 */
public final class QrSvg {

    private static final int QUIET_ZONE = 2;

    private QrSvg() {
    }

    /**
     * An SVG of the text as a QR code with medium error correction, as VERI*FACTU asks for. The
     * SVG scales to whatever size the page gives it.
     */
    public static String of(String text, String title) {
        QrCode qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM);
        int size = qr.size + QUIET_ZONE * 2;
        StringBuilder path = new StringBuilder();
        for (int y = 0; y < qr.size; y++) {
            for (int x = 0; x < qr.size; x++) {
                if (qr.getModule(x, y)) {
                    path.append('M').append(x + QUIET_ZONE).append(',').append(y + QUIET_ZONE).append("h1v1h-1z");
                }
            }
        }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + size + " " + size + "\" role=\"img\""
                + " shape-rendering=\"crispEdges\" aria-label=\"" + escape(title) + "\">"
                + "<rect width=\"100%\" height=\"100%\" fill=\"#fff\"/>"
                + "<path fill=\"#000\" d=\"" + path + "\"/></svg>";
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }
}
