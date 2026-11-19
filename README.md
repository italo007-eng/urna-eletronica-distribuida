# Urna Eletrônica Distribuída

Projeto da disciplina **Redes de Computadores I** (Análise e Desenvolvimento de Sistemas, IFBA Campus Irecê).
Tema 1: **Urna Eletrônica Distribuída**, com arquitetura cliente-servidor sobre **sockets TCP**.

- **Autor(es):** Italo (preencher com o nome completo e os demais integrantes do grupo)
- **Linguagem:** Java 21 (apenas biblioteca padrão, sem dependências externas)
- **Ambiente:** duas máquinas virtuais Linux Debian 13 (VirtualBox) na mesma rede local

---

## 1. Descrição do projeto

O sistema simula uma votação eletrônica. Vários **terminais de votação (clientes)** se conectam a um **servidor centralizador** pela rede. O servidor:

- valida se o eleitor está na lista de aptos;
- impede voto duplo (cada ID vota uma única vez, mesmo que o servidor seja reiniciado);
- confirma a existência do candidato;
- registra o voto e devolve um **recibo único de confirmação (Ack)**;
- mantém registros persistentes em arquivos;
- oferece um comando administrativo local para exibir a **apuração** (parcial ou final).

### Funcionalidades

| Requisito | Como foi atendido |
|---|---|
| Servidor pede IP, porta, candidatos e eleitores | Pergunta no início; os candidatos e eleitores são lidos de arquivos em `dados/` |
| Controle de unicidade | Conjunto de IDs que já votaram, protegido por `synchronized` e salvo em disco |
| Validação do voto e recibo | Valida eleitor e candidato; recibo é um UUID único por voto |
| Apuração no servidor | Comando `apuracao` no prompt `admin>` |
| Cliente pede IP, porta e ID | Pergunta no início, com valores padrão |
| Menu e confirmação do candidato | Lista os candidatos recebidos do servidor e pede confirmação (`s/n`) antes de enviar |
| Aguarda o recibo | O cliente só encerra depois de receber o Ack |
| **Bônus:** Base64 | O número do candidato viaja codificado em Base64 |
| **Bônus:** log de auditoria | `logs/auditoria.log` com data/hora, IP e ID do eleitor, **sem o candidato** |

### Arquitetura

```
 Terminal 1 (cliente)  ─┐
 Terminal 2 (cliente)  ─┼──  TCP :5000  ──▶  Servidor  ──▶  dados/estado/  (votantes + contagem)
 Terminal N (cliente)  ─┘                    (1 thread        logs/auditoria.log
                                              por cliente)
```

- O servidor aceita conexões em um laço e cria **uma thread por cliente** (`ClienteHandler`).
- As regras de votação ficam em `UrnaService`, cujos métodos são `synchronized`: dois terminais nunca registram votos ao mesmo tempo.
- A thread principal do servidor fica livre para o menu administrativo.

### Protocolo de aplicação

Mensagens de texto (UTF-8), uma por linha:

| Cliente envia | Servidor responde |
|---|---|
| `LOGIN idEleitor` | `OK` ou `ERRO NAO_APTO` ou `ERRO JA_VOTOU` |
| `LISTAR` | `CANDIDATOS 1:Ana Souza;2:Bruno Lima;...` |
| `VOTO base64(numero)` | `ACK recibo` ou `ERRO CANDIDATO_INVALIDO` / `JA_VOTOU` / `FORMATO_INVALIDO` |
| `SAIR` | `TCHAU` |

Outros códigos de erro: `NAO_AUTENTICADO` (comando sem LOGIN), `ERRO_INTERNO` (falha ao gravar), `COMANDO_DESCONHECIDO`.

### Sigilo do voto e persistência

O servidor grava dois arquivos em `dados/estado/`, **separados de propósito**:

- `votantes.txt`: ID do eleitor, recibo e horário (quem votou, **sem o candidato**);
- `contagem.csv`: total de votos por candidato (**sem identificar o eleitor**).

Nenhum arquivo liga um eleitor ao seu candidato. Se o servidor for reiniciado, ele recupera esse estado e continua bloqueando quem já votou.

### Estrutura do repositório

```
urna-eletronica-distribuida/
├── README.md
├── .gitignore
├── dados/
│   ├── candidatos.txt        formato: numero;nome (linhas com # são comentários)
│   └── eleitores.txt         um ID de eleitor por linha
├── src/
│   ├── servidor/
│   │   ├── ServidorUrna.java     programa principal, aceita conexões e menu admin
│   │   ├── ClienteHandler.java   atende um cliente (protocolo) em uma thread
│   │   ├── UrnaService.java      regras da urna, unicidade, contagem e persistência
│   │   └── AuditoriaLog.java     log de auditoria sem o candidato
│   └── cliente/
│       └── ClienteUrna.java      terminal de votação
└── logs/                     auditoria.log é gerado aqui (não versionado)
```

---

## 2. Pré-requisitos

- **VirtualBox** instalado no computador hospedeiro.
- **Duas VMs Debian 13**, uma para o servidor e outra para o cliente, na **mesma rede local**.
- Em cada VM: **JDK 21** e **Git**:

```bash
sudo apt update
sudo apt install -y default-jdk git
java -version
javac -version
git --version
```

### Configuração das máquinas virtuais

1. Criar a VM `servidor-urna` com o Debian 13 (instalação em modo texto, com **servidor SSH**).
2. Em **Configurações → Rede → Adaptador 1**, usar **Placa em modo Bridge** (a VM recebe um IP da rede local; o modo NAT não permite que as VMs se enxerguem).
3. **Clonar** a VM para criar a `cliente-urna`, com a opção **"Gerar novos endereços MAC"**, e ajustar o hostname:

```bash
sudo hostnamectl set-hostname cliente-urna
```

4. Descobrir o IP de cada VM e testar a comunicação (os IPs vêm do DHCP e podem mudar; confira com `ip a` antes de cada teste):

```bash
ip a
ping -c 4 IP_DO_SERVIDOR
```

Neste projeto os testes usaram, como exemplo, servidor `192.168.1.5` e cliente `192.168.1.6`.

![Endereços IP das duas VMs](docs/img/01-ip-das-vms.png)

![Ping entre as VMs](docs/img/02-ping-entre-vms.png)

---

## 3. Compilação e execução

### 3.1 Obter o código (nas duas VMs)

Primeira vez:

```bash
git clone https://github.com/italo007-eng/urna-eletronica-distribuida.git
cd urna-eletronica-distribuida
```

Nas próximas vezes, para atualizar:

```bash
cd urna-eletronica-distribuida
git pull
```

### 3.2 Compilar (nas duas VMs)

```bash
mkdir -p out
javac -d out src/servidor/*.java src/cliente/*.java
```

Se não imprimir nada, a compilação foi bem-sucedida. As duas VMs devem estar na **mesma versão** do código.

![Compilação](docs/img/03-compilacao.png)

### 3.3 Executar o servidor (VM `servidor-urna`)

Para começar uma eleição do zero, apague o estado anterior:

```bash
rm -rf dados/estado
java -cp out servidor.ServidorUrna
```

O servidor faz quatro perguntas. **Enter** aceita o valor padrão entre colchetes:

```
=== Servidor da Urna Eletrônica ===
IP para escutar (0.0.0.0 = todas as interfaces) [0.0.0.0]:
Porta [5000]:
Arquivo de candidatos [dados/candidatos.txt]:
Arquivo de eleitores aptos [dados/eleitores.txt]:
Servidor ouvindo em 0.0.0.0:5000
Comandos: apuracao | ajuda | sair
admin>
```

Comandos administrativos (digitados no prompt `admin>`):

| Comando | Ação |
|---|---|
| `apuracao` | Mostra a contagem parcial ou final dos votos |
| `ajuda` | Lista os comandos |
| `sair` | Encerra o servidor |

![Servidor em execução](docs/img/04-servidor-iniciado.png)

### 3.4 Executar o cliente (VM `cliente-urna`)

```bash
java -cp out cliente.ClienteUrna
```

Informe o **IP do servidor**, a **porta** (`5000`) e o **ID do eleitor**. Exemplo de uma votação:

```
=== Terminal de Votação ===
IP do servidor [127.0.0.1]: IP_DO_SERVIDOR
Porta [5000]:
ID do eleitor: 1003
Eleitor 1003 identificado.

--- CANDIDATOS ---
  [1] Ana Souza
  [2] Bruno Lima
  [3] Carla Mendes
  [0] Voto em Branco
Digite o número do seu candidato: 2
Você escolheu: [2] Bruno Lima
Confirmar voto? (s/n): s
Enviando voto e aguardando o recibo do servidor...

VOTO REGISTRADO COM SUCESSO!
Recibo de confirmação: 81EA2C67-6E77-4935-8B97-D5DB461DBA4C
Guarde este código como comprovante.
Pressione Enter para liberar o terminal...
```

![Cliente votando](docs/img/05-cliente-votando.png)

### 3.5 Configuração dos dados

Para alterar candidatos e eleitores, edite os arquivos em `dados/` e reinicie o servidor.

`dados/candidatos.txt`:

```
# formato: numero;nome
1;Ana Souza
2;Bruno Lima
3;Carla Mendes
0;Voto em Branco
```

`dados/eleitores.txt` (um ID por linha): `1001` a `1005` no exemplo.

---

## 4. Testes realizados

| # | Cenário | Resultado esperado | Evidência |
|---|---|---|---|
| 1 | Eleitor apto vota | Recibo exibido e voto contado | ![](docs/img/05-cliente-votando.png) |
| 2 | Mesmo eleitor tenta votar de novo | Acesso negado: "este eleitor já votou" | ![](docs/img/06-voto-duplo.png) |
| 3 | ID que não consta na lista | Acesso negado: "não consta na lista de eleitores aptos" | ![](docs/img/07-eleitor-inexistente.png) |
| 4 | Eleitor responde `n` na confirmação | Volta ao menu, nada é enviado | |
| 5 | Comando `apuracao` no servidor | Totais por candidato | ![](docs/img/08-apuracao.png) |
| 6 | Dois terminais votando ao mesmo tempo | Ambos atendidos, sem voto duplo | |
| 7 | Reinício do servidor | Quem já votou continua bloqueado | |

Exemplo de apuração:

```
===== APURAÇÃO =====
1 - Ana Souza            0 voto(s)
2 - Bruno Lima           1 voto(s)
3 - Carla Mendes         0 voto(s)
0 - Voto em Branco       0 voto(s)
Total de votos: 1 | Aptos: 5 | Faltam: 4
```

---

## 5. Recursos opcionais (bônus)

### 5.1 Codificação Base64 do voto

O cliente envia o número do candidato codificado. Capturando o tráfego no servidor com `tcpdump`:

```bash
sudo apt install -y tcpdump
sudo tcpdump -A -l -i enp0s3 port 5000 | grep -a -E "LOGIN|LISTAR|VOTO|CANDIDATOS|ACK|ERRO"
```

o voto aparece como `VOTO Mg==` em vez de `VOTO 2`. Se o servidor receber o voto em texto claro ou com Base64 inválido, responde `ERRO FORMATO_INVALIDO`.

![Captura do tráfego com tcpdump](docs/img/09-tcpdump-base64.png)

> **Limitação:** Base64 é uma **codificação**, não uma criptografia. Qualquer pessoa decodifica o valor, por exemplo com `echo Mg== | base64 -d`. Ele evita a leitura direta do voto no tráfego, mas não oferece sigilo real. Em um sistema de verdade seria necessário **TLS**. Além disso, o `LOGIN` e a lista de candidatos continuam em texto claro.

### 5.2 Log de auditoria

O arquivo `logs/auditoria.log` registra data/hora, IP do terminal, ID do eleitor e o evento, **sem nunca gravar o candidato**:

```
2026-11-19 18:13:40 | 192.168.1.6 | eleitor=1003 | LOGIN_OK | -
2026-11-19 18:13:52 | 192.168.1.6 | eleitor=1003 | VOTO_REGISTRADO | recibo=81EA2C67-6E77-4935-8B97-D5DB461DBA4C
2026-11-19 18:14:10 | 192.168.1.6 | eleitor=1003 | LOGIN_NEGADO | JA_VOTOU
```

Para consultar:

```bash
cat logs/auditoria.log
```

São registradas também as tentativas recusadas. O texto vindo do cliente é higienizado antes de ser gravado, para que ninguém consiga forjar linhas no log.

![Log de auditoria](docs/img/10-auditoria-log.png)

---

## 6. Limitações e melhorias possíveis

- Os endereços IP vêm de DHCP e podem mudar; para um ambiente fixo, configurar IP estático ou reserva no roteador.
- Base64 não é criptografia (ver seção 5.1); o ideal seria usar TLS (`SSLSocket`).
- A identificação do eleitor é apenas por ID, sem senha ou biometria.
- O arquivo de contagem é regravado a cada voto, o que é simples e suficiente para uma turma, mas não escala para milhares de votos.
- O servidor aceita até 120 s de inatividade por conexão; o cliente espera até 5 s para conectar e 30 s por resposta.

## 7. Histórico de desenvolvimento

O histórico de commits está visível no próprio repositório (`git log --oneline`).

![Histórico de commits](docs/img/11-git-log.png)
