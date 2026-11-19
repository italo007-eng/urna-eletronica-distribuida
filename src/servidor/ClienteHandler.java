package servidor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Atende UM terminal de votação (uma conexão TCP) em uma thread própria.
 *
 * <p>Protocolo (uma mensagem por linha):</p>
 * <pre>
 * LOGIN idEleitor        -> OK | ERRO NAO_APTO | ERRO JA_VOTOU
 * LISTAR                 -> CANDIDATOS 1:Ana;2:Bruno
 * VOTO base64(numero)    -> ACK recibo | ERRO CANDIDATO_INVALIDO | ERRO JA_VOTOU
 *                           | ERRO FORMATO_INVALIDO
 * SAIR                   -> TCHAU
 * </pre>
 *
 * <p>O número do candidato viaja codificado em Base64 (não em texto claro).
 * Atenção: Base64 é codificação, não criptografia.</p>
 */
public class ClienteHandler implements Runnable {

    private static final int TIMEOUT_MS = 120_000;

    private final Socket socket;
    private final UrnaService urna;
    private final AuditoriaLog auditoria;

    public ClienteHandler(Socket socket, UrnaService urna, AuditoriaLog auditoria) {
        this.socket = socket;
        this.urna = urna;
        this.auditoria = auditoria;
    }

    @Override
    public void run() {
        String origem = socket.getRemoteSocketAddress().toString();
        String ip = socket.getInetAddress().getHostAddress();
        System.out.println("[+] Conexão de " + origem);

        try (BufferedReader entrada = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter saida = new PrintWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

            socket.setSoTimeout(TIMEOUT_MS); // não prende a thread se o cliente sumir
            String idEleitor = null;         // só preenchido após LOGIN bem-sucedido
            String linha;

            while ((linha = entrada.readLine()) != null) {
                String[] partes = linha.trim().split("\\s+", 2);
                String comando = partes[0].toUpperCase();
                String argumento = partes.length > 1 ? partes[1].trim() : "";

                switch (comando) {
                    case "LOGIN" -> {
                        try {
                            urna.validarEleitor(argumento);
                            idEleitor = argumento;
                            saida.println("OK");
                            auditoria.registrar(argumento, "LOGIN_OK", "-", ip);
                            System.out.println("[" + origem + "] login do eleitor " + argumento);
                        } catch (UrnaService.UrnaException e) {
                            saida.println("ERRO " + e.getCodigo());
                            auditoria.registrar(argumento, "LOGIN_NEGADO", e.getCodigo(), ip);
                        }
                    }
                    case "LISTAR" -> {
                        if (idEleitor == null) {
                            saida.println("ERRO NAO_AUTENTICADO");
                        } else {
                            saida.println("CANDIDATOS " + urna.candidatosFormatados());
                        }
                    }
                    case "VOTO" -> {
                        if (idEleitor == null) {
                            saida.println("ERRO NAO_AUTENTICADO");
                            auditoria.registrar("-", "VOTO_REJEITADO", "NAO_AUTENTICADO", ip);
                            break;
                        }
                        try {
                            // O argumento chega em Base64: decodifica para obter o número.
                            String numeroTexto = new String(
                                    Base64.getDecoder().decode(argumento), StandardCharsets.UTF_8).trim();
                            int numero = Integer.parseInt(numeroTexto);

                            String recibo = urna.registrarVoto(idEleitor, numero);
                            saida.println("ACK " + recibo);
                            // Nem o console nem o log mostram o candidato: sigilo do voto.
                            auditoria.registrar(idEleitor, "VOTO_REGISTRADO", "recibo=" + recibo, ip);
                            System.out.println("[" + origem + "] voto registrado do eleitor "
                                    + idEleitor + " (recibo " + recibo + ")");
                        } catch (IllegalArgumentException e) {
                            // Cobre Base64 inválido e número inválido (NumberFormatException
                            // é um tipo de IllegalArgumentException).
                            saida.println("ERRO FORMATO_INVALIDO");
                            auditoria.registrar(idEleitor, "VOTO_REJEITADO", "FORMATO_INVALIDO", ip);
                        } catch (UrnaService.UrnaException e) {
                            saida.println("ERRO " + e.getCodigo());
                            auditoria.registrar(idEleitor, "VOTO_REJEITADO", e.getCodigo(), ip);
                        }
                    }
                    case "SAIR" -> {
                        saida.println("TCHAU");
                        return;
                    }
                    default -> saida.println("ERRO COMANDO_DESCONHECIDO");
                }
            }
        } catch (IOException e) {
            System.out.println("[!] Conexão " + origem + " encerrada: " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignorado) {
                // nada a fazer
            }
            System.out.println("[-] Desconectado " + origem);
        }
    }
}
