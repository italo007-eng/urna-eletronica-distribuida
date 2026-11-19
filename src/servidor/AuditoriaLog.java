package servidor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Log de auditoria: registra QUEM fez o quê e QUANDO, sem nunca gravar
 * o candidato escolhido (preserva o sigilo do voto).
 *
 * <p>Formato de cada linha:
 * {@code data hora | ip | eleitor=ID | EVENTO | detalhe}</p>
 */
public class AuditoriaLog {

    private static final DateTimeFormatter FORMATO_DATA =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int TAMANHO_MAXIMO_CAMPO = 60;

    private final Path arquivo;

    /**
     * @param arquivo caminho do arquivo de log (a pasta é criada se não existir)
     * @throws IOException se a pasta não puder ser criada
     */
    public AuditoriaLog(Path arquivo) throws IOException {
        this.arquivo = arquivo;
        if (arquivo.getParent() != null) {
            Files.createDirectories(arquivo.getParent());
        }
    }

    /**
     * Acrescenta uma linha ao log. Falhas de escrita não derrubam o servidor.
     *
     * @param idEleitor ID informado pelo eleitor (ou "-" se ainda não houver)
     * @param evento    nome curto do evento, ex.: VOTO_REGISTRADO
     * @param detalhe   informação extra (recibo, motivo da recusa). NUNCA o candidato.
     * @param ip        endereço IP do terminal
     */
    public synchronized void registrar(String idEleitor, String evento, String detalhe, String ip) {
        String linha = LocalDateTime.now().format(FORMATO_DATA)
                + " | " + ip
                + " | eleitor=" + limpar(idEleitor)
                + " | " + evento
                + " | " + limpar(detalhe)
                + System.lineSeparator();
        try {
            Files.writeString(arquivo, linha, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("[!] Falha ao gravar o log de auditoria: " + e.getMessage());
        }
    }

    /** Remove caracteres de controle e '|' e limita o tamanho, para o log não ser forjado. */
    private static String limpar(String texto) {
        if (texto == null || texto.isEmpty()) {
            return "-";
        }
        String limpo = texto.replaceAll("[\\p{Cntrl}|]", "_");
        return limpo.length() > TAMANHO_MAXIMO_CAMPO ? limpo.substring(0, TAMANHO_MAXIMO_CAMPO) : limpo;
    }
}
