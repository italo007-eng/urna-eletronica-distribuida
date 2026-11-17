package servidor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Atende UM terminal de votação (uma conexão TCP) em uma thread própria.
 *
 * <p>Protocolo (uma mensagem por linha):</p>
 * <pre>
 * LOGIN idEleitor   -> OK | ERRO NAO_APTO | ERRO JA_VOTOU
 * LISTAR            -> CANDIDATOS 1:Ana;2:Bruno
 * VOTO numero       -> ACK recibo | ERRO CANDIDATO_INVALIDO | ERRO JA_VOTOU
 * SAIR              -> TCHAU
 * </pre>
 */
public class ClienteHandler implements Runnable {

    private static final int TIMEOUT_MS = 120_000;

    private final Socket socket;
    private final UrnaService urna;

    public ClienteHandler(Socket socket, UrnaService urna) {
        this.socket = socket;
        this.urna = urna;
    }

    @Override
    public void run() {
        String origem = socket.getRemoteSocketAddress().toString();
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
                            System.out.println("[" + origem + "] login do eleitor " + argumento);
                        } catch (UrnaService.UrnaException e) {
                            saida.println("ERRO " + e.getCodigo());
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
                            break;
                        }
                        try {
                            int numero = Integer.parseInt(argumento);
                            String recibo = urna.registrarVoto(idEleitor, numero);
                            saida.println("ACK " + recibo);
                            // Não imprime o candidato: preserva o sigilo do voto.
                            System.out.println("[" + origem + "] voto registrado do eleitor "
                                    + idEleitor + " (recibo " + recibo + ")");
                        } catch (NumberFormatException e) {
                            saida.println("ERRO CANDIDATO_INVALIDO");
                        } catch (UrnaService.UrnaException e) {
                            saida.println("ERRO " + e.getCodigo());
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
