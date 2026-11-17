package servidor;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;

/**
 * Servidor da Urna Eletrônica Distribuída.
 *
 * <p>Pede IP, porta e arquivos de candidatos/eleitores, aceita conexões
 * TCP (uma thread por terminal) e oferece um menu administrativo local
 * para ver a apuração.</p>
 */
public class ServidorUrna {

    public static void main(String[] args) throws IOException {
        Scanner teclado = new Scanner(System.in);

        System.out.println("=== Servidor da Urna Eletrônica ===");
        String ip = perguntar(teclado, "IP para escutar (0.0.0.0 = todas as interfaces)", "0.0.0.0");
        int porta = Integer.parseInt(perguntar(teclado, "Porta", "5000"));
        Path candidatos = Paths.get(perguntar(teclado, "Arquivo de candidatos", "dados/candidatos.txt"));
        Path eleitores = Paths.get(perguntar(teclado, "Arquivo de eleitores aptos", "dados/eleitores.txt"));

        UrnaService urna = new UrnaService(candidatos, eleitores, Paths.get("dados/estado"));
        ServerSocket servidor = new ServerSocket(porta, 50, InetAddress.getByName(ip));
        System.out.println("Servidor ouvindo em " + ip + ":" + porta);

        // Thread que aceita conexões; cada cliente ganha sua própria thread.
        Thread aceitador = new Thread(() -> {
            while (!servidor.isClosed()) {
                try {
                    Socket cliente = servidor.accept();
                    new Thread(new ClienteHandler(cliente, urna)).start();
                } catch (IOException e) {
                    if (!servidor.isClosed()) {
                        System.out.println("Erro ao aceitar conexão: " + e.getMessage());
                    }
                }
            }
        });
        aceitador.setDaemon(true);
        aceitador.start();

        // Menu administrativo local (thread principal).
        System.out.println("Comandos: apuracao | ajuda | sair");
        while (true) {
            System.out.print("admin> ");
            if (!teclado.hasNextLine()) {
                break;
            }
            String comando = teclado.nextLine().trim().toLowerCase();
            if (comando.equals("apuracao")) {
                System.out.print(urna.apuracao());
            } else if (comando.equals("ajuda")) {
                System.out.println("apuracao - mostra a contagem parcial/final\nsair - encerra o servidor");
            } else if (comando.equals("sair")) {
                break;
            } else if (!comando.isEmpty()) {
                System.out.println("Comando desconhecido. Digite 'ajuda'.");
            }
        }
        servidor.close();
        System.out.println("Servidor encerrado.");
    }

    /** Mostra uma pergunta com valor padrão; Enter aceita o padrão. */
    private static String perguntar(Scanner teclado, String texto, String padrao) {
        System.out.print(texto + " [" + padrao + "]: ");
        String resposta = teclado.nextLine().trim();
        return resposta.isEmpty() ? padrao : resposta;
    }
}
