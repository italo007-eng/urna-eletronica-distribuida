package cliente;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Scanner;

/**
 * Terminal de votação (cliente) da Urna Eletrônica Distribuída.
 *
 * <p>Fluxo: pede IP, porta e ID do eleitor, conecta ao servidor, faz LOGIN,
 * obtém a lista de candidatos, pede a escolha, confirma o nome na tela,
 * envia o voto e só encerra depois de receber o recibo (ACK) do servidor.</p>
 */
public class ClienteUrna {

    private static final int TIMEOUT_CONEXAO_MS = 5_000;
    private static final int TIMEOUT_LEITURA_MS = 30_000;

    public static void main(String[] args) {
        Scanner teclado = new Scanner(System.in);

        System.out.println("=== Terminal de Votação ===");
        String ip = perguntar(teclado, "IP do servidor", "127.0.0.1");
        int porta = lerPorta(teclado);
        String idEleitor = lerObrigatorio(teclado, "ID do eleitor");

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, porta), TIMEOUT_CONEXAO_MS);
            socket.setSoTimeout(TIMEOUT_LEITURA_MS);

            BufferedReader entrada = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter saida = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            // 1) Identificação do eleitor
            String resposta = enviar(saida, entrada, "LOGIN " + idEleitor);
            if (!resposta.equals("OK")) {
                System.out.println("Acesso negado: " + traduzirErro(resposta));
                return;
            }
            System.out.println("Eleitor " + idEleitor + " identificado.");

            // 2) Lista de candidatos
            resposta = enviar(saida, entrada, "LISTAR");
            if (!resposta.startsWith("CANDIDATOS ")) {
                System.out.println("Não foi possível obter os candidatos: " + traduzirErro(resposta));
                return;
            }
            Map<Integer, String> candidatos = interpretarCandidatos(resposta.substring(11));

            // 3) Escolha + confirmação na tela
            int escolha = escolherCandidato(teclado, candidatos);

            // 4) Envio do voto e espera do recibo
            System.out.println("Enviando voto e aguardando o recibo do servidor...");
            // O número do candidato é enviado em Base64, não em texto claro.
            String votoCodificado = Base64.getEncoder()
                    .encodeToString(String.valueOf(escolha).getBytes(StandardCharsets.UTF_8));
            resposta = enviar(saida, entrada, "VOTO " + votoCodificado);
            if (resposta.startsWith("ACK ")) {
                System.out.println();
                System.out.println("VOTO REGISTRADO COM SUCESSO!");
                System.out.println("Recibo de confirmação: " + resposta.substring(4));
                System.out.println("Guarde este código como comprovante.");
            } else {
                System.out.println("Voto não registrado: " + traduzirErro(resposta));
            }

            // 5) Encerramento educado
            try {
                enviar(saida, entrada, "SAIR");
            } catch (IOException ignorado) {
                // o voto já foi tratado; falha ao sair não importa
            }
            System.out.print("Pressione Enter para liberar o terminal...");
            if (teclado.hasNextLine()) {
                teclado.nextLine();
            }

        } catch (ConnectException e) {
            System.out.println("Não foi possível conectar em " + ip + ":" + porta
                    + ". O servidor está rodando e o IP/porta estão corretos?");
        } catch (SocketTimeoutException e) {
            System.out.println("O servidor demorou demais para responder.");
        } catch (IOException e) {
            System.out.println("Erro de comunicação: " + e.getMessage());
        }
    }

    /** Envia uma linha ao servidor e devolve a linha de resposta. */
    private static String enviar(PrintWriter saida, BufferedReader entrada, String mensagem)
            throws IOException {
        saida.println(mensagem);
        String resposta = entrada.readLine();
        if (resposta == null) {
            throw new IOException("o servidor encerrou a conexão");
        }
        return resposta.trim();
    }

    /** Converte "1:Ana;2:Bruno" em um mapa numero -> nome. */
    private static Map<Integer, String> interpretarCandidatos(String texto) {
        Map<Integer, String> mapa = new LinkedHashMap<>();
        for (String item : texto.split(";")) {
            String[] p = item.split(":", 2);
            if (p.length == 2) {
                mapa.put(Integer.parseInt(p[0].trim()), p[1].trim());
            }
        }
        return mapa;
    }

    /** Mostra o menu, lê a escolha e só devolve depois que o eleitor confirmar. */
    private static int escolherCandidato(Scanner teclado, Map<Integer, String> candidatos) {
        while (true) {
            System.out.println();
            System.out.println("--- CANDIDATOS ---");
            for (Map.Entry<Integer, String> c : candidatos.entrySet()) {
                System.out.println("  [" + c.getKey() + "] " + c.getValue());
            }
            System.out.print("Digite o número do seu candidato: ");
            String entrada = teclado.nextLine().trim();

            int numero;
            try {
                numero = Integer.parseInt(entrada);
            } catch (NumberFormatException e) {
                System.out.println("Entrada inválida. Digite apenas o número.");
                continue;
            }
            if (!candidatos.containsKey(numero)) {
                System.out.println("Esse número não está na lista.");
                continue;
            }

            System.out.println("Você escolheu: [" + numero + "] " + candidatos.get(numero));
            System.out.print("Confirmar voto? (s/n): ");
            if (teclado.nextLine().trim().equalsIgnoreCase("s")) {
                return numero;
            }
            System.out.println("Voto não confirmado. Escolha novamente.");
        }
    }

    /** Traduz o código de erro do servidor para uma mensagem amigável. */
    private static String traduzirErro(String resposta) {
        String codigo = resposta.startsWith("ERRO ") ? resposta.substring(5) : resposta;
        return switch (codigo) {
            case "NAO_APTO" -> "este ID não consta na lista de eleitores aptos.";
            case "JA_VOTOU" -> "este eleitor já votou.";
            case "CANDIDATO_INVALIDO" -> "candidato inválido.";
            case "NAO_AUTENTICADO" -> "eleitor não identificado.";
            case "FORMATO_INVALIDO" -> "mensagem de voto em formato inválido.";
            case "ERRO_INTERNO" -> "erro interno no servidor. Tente novamente.";
            default -> codigo;
        };
    }

    private static String perguntar(Scanner teclado, String texto, String padrao) {
        System.out.print(texto + " [" + padrao + "]: ");
        String resposta = teclado.nextLine().trim();
        return resposta.isEmpty() ? padrao : resposta;
    }

    private static String lerObrigatorio(Scanner teclado, String texto) {
        while (true) {
            System.out.print(texto + ": ");
            String resposta = teclado.nextLine().trim();
            if (!resposta.isEmpty()) {
                return resposta;
            }
            System.out.println("Este campo é obrigatório.");
        }
    }

    private static int lerPorta(Scanner teclado) {
        while (true) {
            String texto = perguntar(teclado, "Porta", "5000");
            try {
                int porta = Integer.parseInt(texto);
                if (porta >= 1 && porta <= 65535) {
                    return porta;
                }
            } catch (NumberFormatException e) {
                // cai na mensagem abaixo
            }
            System.out.println("Porta inválida (use 1 a 65535).");
        }
    }
}
