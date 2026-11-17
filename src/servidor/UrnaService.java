package servidor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Regras de negócio da urna: candidatos, eleitores aptos, controle de
 * unicidade do voto, contagem e persistência em arquivos.
 *
 * <p>Todos os métodos públicos são {@code synchronized}: como o servidor
 * atende vários clientes em threads diferentes, isso garante que dois
 * terminais nunca registrem votos ao mesmo tempo (evita voto duplo).</p>
 */
public class UrnaService {

    /** Erro de regra de negócio, com um código curto enviado ao cliente. */
    public static class UrnaException extends Exception {
        private final String codigo;

        public UrnaException(String codigo) {
            super(codigo);
            this.codigo = codigo;
        }

        public String getCodigo() {
            return codigo;
        }
    }

    private static final DateTimeFormatter FORMATO_DATA =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Map<Integer, String> candidatos = new LinkedHashMap<>();
    private final Map<Integer, Integer> contagem = new LinkedHashMap<>();
    private final Set<String> eleitoresAptos = new HashSet<>();
    private final Set<String> jaVotaram = new HashSet<>();

    /** IDs de quem votou + recibo + data (NÃO guarda o candidato). */
    private final Path arquivoVotantes;
    /** Total de votos por candidato (NÃO guarda quem votou em quem). */
    private final Path arquivoContagem;

    /**
     * Carrega candidatos e eleitores e restaura o estado salvo (se existir).
     *
     * @param arquivoCandidatos arquivo com linhas "numero;nome"
     * @param arquivoEleitores  arquivo com um ID de eleitor por linha
     * @param pastaEstado       pasta onde o estado da votação é salvo
     * @throws IOException se algum arquivo não puder ser lido
     */
    public UrnaService(Path arquivoCandidatos, Path arquivoEleitores, Path pastaEstado)
            throws IOException {
        Files.createDirectories(pastaEstado);
        this.arquivoVotantes = pastaEstado.resolve("votantes.txt");
        this.arquivoContagem = pastaEstado.resolve("contagem.csv");

        for (String linha : lerLinhas(arquivoCandidatos)) {
            String[] p = linha.split(";", 2);
            if (p.length != 2) {
                throw new IOException("Linha inválida em candidatos: " + linha);
            }
            int numero = Integer.parseInt(p[0].trim());
            candidatos.put(numero, p[1].trim());
            contagem.put(numero, 0);
        }
        eleitoresAptos.addAll(lerLinhas(arquivoEleitores));
        restaurarEstado();
    }

    /** Lê um arquivo ignorando linhas vazias e comentários (#). */
    private static List<String> lerLinhas(Path arquivo) throws IOException {
        List<String> resultado = new ArrayList<>();
        for (String linha : Files.readAllLines(arquivo, StandardCharsets.UTF_8)) {
            String l = linha.trim();
            if (!l.isEmpty() && !l.startsWith("#")) {
                resultado.add(l);
            }
        }
        return resultado;
    }

    /** Se o servidor foi reiniciado, recupera quem já votou e a contagem. */
    private void restaurarEstado() throws IOException {
        if (Files.exists(arquivoVotantes)) {
            for (String linha : lerLinhas(arquivoVotantes)) {
                jaVotaram.add(linha.split(";")[0]);
            }
        }
        if (Files.exists(arquivoContagem)) {
            for (String linha : lerLinhas(arquivoContagem)) {
                String[] p = linha.split(";");
                int numero = Integer.parseInt(p[0]);
                if (contagem.containsKey(numero)) {
                    contagem.put(numero, Integer.parseInt(p[1]));
                }
            }
        }
    }

    /**
     * Lista de candidatos no formato do protocolo: "1:Ana;2:Bruno".
     */
    public synchronized String candidatosFormatados() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, String> c : candidatos.entrySet()) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(c.getKey()).append(':').append(c.getValue());
        }
        return sb.toString();
    }

    /**
     * Verifica se o eleitor existe e ainda não votou.
     *
     * @throws UrnaException NAO_APTO ou JA_VOTOU
     */
    public synchronized void validarEleitor(String idEleitor) throws UrnaException {
        if (!eleitoresAptos.contains(idEleitor)) {
            throw new UrnaException("NAO_APTO");
        }
        if (jaVotaram.contains(idEleitor)) {
            throw new UrnaException("JA_VOTOU");
        }
    }

    /**
     * Registra o voto de forma atômica e devolve o recibo (Ack).
     *
     * @param idEleitor      ID do eleitor autenticado
     * @param numeroCandidato número do candidato escolhido
     * @return código único de recibo
     * @throws UrnaException NAO_APTO, JA_VOTOU, CANDIDATO_INVALIDO ou ERRO_INTERNO
     */
    public synchronized String registrarVoto(String idEleitor, int numeroCandidato)
            throws UrnaException {
        validarEleitor(idEleitor);
        if (!candidatos.containsKey(numeroCandidato)) {
            throw new UrnaException("CANDIDATO_INVALIDO");
        }

        String recibo = UUID.randomUUID().toString().toUpperCase();
        int novoTotal = contagem.get(numeroCandidato) + 1;

        try {
            // 1) registra QUEM votou (sem o candidato)
            String registro = idEleitor + ";" + recibo + ";"
                    + LocalDateTime.now().format(FORMATO_DATA) + System.lineSeparator();
            Files.writeString(arquivoVotantes, registro, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            // 2) atualiza a contagem (sem identificar o eleitor)
            contagem.put(numeroCandidato, novoTotal);
            salvarContagem();
        } catch (IOException e) {
            contagem.put(numeroCandidato, novoTotal - 1);
            throw new UrnaException("ERRO_INTERNO");
        }

        jaVotaram.add(idEleitor);
        return recibo;
    }

    /** Regrava o arquivo de contagem inteiro. */
    private void salvarContagem() throws IOException {
        List<String> linhas = new ArrayList<>();
        for (Map.Entry<Integer, Integer> c : contagem.entrySet()) {
            linhas.add(c.getKey() + ";" + c.getValue());
        }
        Files.write(arquivoContagem, linhas, StandardCharsets.UTF_8);
    }

    /** Texto da apuração (parcial ou final) para o terminal do servidor. */
    public synchronized String apuracao() {
        StringBuilder sb = new StringBuilder();
        int total = 0;
        sb.append("===== APURAÇÃO =====").append(System.lineSeparator());
        for (Map.Entry<Integer, String> c : candidatos.entrySet()) {
            int votos = contagem.get(c.getKey());
            total += votos;
            sb.append(String.format("%d - %-20s %d voto(s)%n", c.getKey(), c.getValue(), votos));
        }
        sb.append(String.format("Total de votos: %d | Aptos: %d | Faltam: %d%n",
                total, eleitoresAptos.size(), eleitoresAptos.size() - jaVotaram.size()));
        return sb.toString();
    }
}
