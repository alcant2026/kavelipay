#!/usr/bin/env bash
# Prova que o QR Code do app é legível de verdade.
#
# Gera a matriz com o mesmo ZXing e as mesmas hints que `KpQrCode` usa
# (ERROR_CORRECTION=M, MARGIN=1, escala inteira), amplia por pixel como o app
# faz e decodifica de volta. Um QR que "renderiza bonito" e não decodifica é
# pior que placeholder: parece funcionar e não funciona.
#
# Não depende do módulo `javase` (swing/ImageIO): o `LuminanceSource` é
# montado direto da matriz, e o app também não usa `javase`.
set -euo pipefail

cd "$(dirname "$0")/.."
ZXING=$(find ~/.gradle/caches/modules-2/files-2.1/com.google.zxing/core -name "core-*.jar" 2>/dev/null | grep -v sources | head -1)
if [ -z "$ZXING" ]; then
  echo "ZXing core não encontrado no cache do Gradle — rode ./gradlew :app:assembleRelease antes"; exit 1
fi

PAYLOAD="${1:-kavelipay://cobranca/84eb8382-f322-4957-b93a-ce93ae049663?valor=80.00}"
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

cat > "$OUT/VerificarQr.java" <<'JAVA'
import com.google.zxing.*;
import com.google.zxing.common.*;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.*;

public class VerificarQr {

    /** LuminanceSource mínima: a matriz já é a imagem (preto e branco puro). */
    static final class MatrizLuminance extends LuminanceSource {
        private final BitMatrix m;
        MatrizLuminance(BitMatrix m) { super(m.getWidth(), m.getHeight()); this.m = m; }
        public byte[] getRow(int y, byte[] linha) {
            byte[] out = linha == null ? new byte[getWidth()] : linha;
            for (int x = 0; x < getWidth(); x++) out[x] = (byte) (m.get(x, y) ? 0 : 0xFF);
            return out;
        }
        public byte[] getMatrix() {
            byte[] out = new byte[getWidth() * getHeight()];
            for (int y = 0; y < getHeight(); y++)
                for (int x = 0; x < getWidth(); x++)
                    out[y * getWidth() + x] = (byte) (m.get(x, y) ? 0 : 0xFF);
            return out;
        }
    }

    public static void main(String[] args) throws Exception {
        String payload = args[0];

        Map<EncodeHintType,Object> hints = new HashMap<>();
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 1);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");

        BitMatrix m = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, hints);
        System.out.println("modulos:  " + m.getWidth() + "x" + m.getHeight());

        // Mesma regra de `escalaPara` no app: maior inteiro que cabe em 600px.
        int escala = Math.max(1, 600 / m.getWidth());
        int lado = m.getWidth() * escala;
        System.out.println("escala:   " + escala + "px/modulo");
        System.out.println("bitmap:   " + lado + "x" + lado);

        BitMatrix ampliado = new BitMatrix(lado, lado);
        for (int y = 0; y < lado; y++)
            for (int x = 0; x < lado; x++)
                if (m.get(x / escala, y / escala)) ampliado.set(x, y);

        Map<DecodeHintType,Object> dhints = new HashMap<>();
        dhints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);

        Result r = new MultiFormatReader()
            .decode(new BinaryBitmap(new HybridBinarizer(new MatrizLuminance(ampliado))), dhints);

        String lido = r.getText();
        if (!lido.equals(payload)) {
            System.out.println("FALHOU: payload diferente");
            System.out.println("  esperado: " + payload);
            System.out.println("  lido:     " + lido);
            System.exit(1);
        }
        System.out.println("decodific: " + lido);
        System.out.println("OK: QR gerado e decodificado, payload idêntico");
    }
}
JAVA

javac -cp "$ZXING" -d "$OUT" "$OUT/VerificarQr.java"
java -cp "$ZXING:$OUT" VerificarQr "$PAYLOAD"