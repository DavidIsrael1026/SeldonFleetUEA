# Seldon Fleet — Desafio Técnico (UEA / Motorola)

Este repositório contém a minha entrega para o desafio técnico do **Projeto Seldon**. O aplicativo foi feito em **Kotlin** com **Jetpack Compose** para ler os dados dos 30 celulares do arquivo `assets/frota.json`, funcionando 100% offline (em Modo Avião).

O objetivo principal foi isolar a conta matemática de classificação na pasta `domain/` (sem misturar com coisas do Android), converter as unidades de energia de `mWh` para `mAh`, evitar erros de divisão por zero nos dados incompletos e mostrar o status de cada aparelho na tela (`OK`, `ATENÇÃO`, `CRÍTICO` ou `SEM DADOS`).

---

## 1. Como Executar o Projeto e os Testes

Para rodar o app, basta abrir a pasta do projeto no **Android Studio** (com **JDK 17** configurado) e executar em um celular físico via USB ou emulador.

Comandos para rodar pelo terminal na raiz do projeto:
- **Rodar os testes unitários na JVM (sem emulador — REQ-08):**
  * Windows: `.\gradlew.bat testDebugUnitTest`
  * Linux/Mac: `./gradlew testDebugUnitTest`
- **Rodar a verificação de código (Lint — REQ-09):**
  * Windows: `.\gradlew.bat lintDebug`
  * Linux/Mac: `./gradlew lintDebug`

---

## 2. Capturas de Tela (Seção 8)

Rodando o aplicativo no meu celular em Modo Avião com o arquivo `frota.json`, os 30 aparelhos foram classificados como: **16 OK**, **8 ATENÇÃO**, **4 CRÍTICO** e **2 SEM DADOS** (fechando os 30 registros do arquivo).

| Tela 1: Lista da Frota, Filtros e Favoritos | Tela 2: Detalhes e Justificativa |
| :---: | :---: |
| ![Tela 1](docs/screenshots/tela1_lista.png) | ![Tela 2](docs/screenshots/tela2_detalhe.png) |

---

## 3. Como o Código Foi Organizado e Decisões Tomadas

Segui a divisão em 3 camadas pedida na Seção 6 do desafio:

- **`domain/` (Regra de negócio):** Guarda os modelos (`Device`, `DeviceStatus`), o contrato do repositório (`DeviceRepository`) e o cálculo de classificação (`ClassifyDevice`). Não tem nenhum `import android.*`, o que permite rodar os testes direto no computador.
- **`data/` (Leitura e gravação):** O `AssetFleetSource` abre o arquivo JSON usando o Gson e joga os dados brutos no `DeviceDto`. Depois, o `DeviceMapper` converte os aparelhos que vieram em `mWh` para `mAh` (dividindo pela tensão nominal). Por fim, o `FleetRepositoryImpl` junta essa lista já corrigida com os favoritos salvos no `DataStore`.
- **`ui/` (Telas):** O `FleetListViewModel` busca os dados no repositório, passa pela regra de cálculo do domínio e manda o resultado pronto para as duas telas em Compose (`FleetListScreen` e `DeviceDetailScreen`).

### Escolhas de Projeto (Trade-offs)
1. **Aparelhos com dados zerados como `INDETERMINADO` (`SEM DADOS`):** Em vez de ignorar ou apagar os aparelhos com bateria `0` (`DEV-0311`) ou tempo de tela `0` (`DEV-0745`), preferi manter os dois na lista como `SEM DADOS`. Assim a soma dos filtros continua batendo com os 30 aparelhos do arquivo e quem estiver olhando o painel sabe que aqueles celulares estão com falha nos dados.
2. **Navegação controlada pelo ViewModel:** Como o aplicativo só tem duas telas, guardei qual aparelho foi clicado dentro do próprio `FleetListViewModel` e usei o `BackHandler` para tratar o botão de voltar. Fiz assim porque é um jeito simples de garantir que, ao voltar da Tela 2 para a Tela 1, o filtro selecionado não se perca (REQ-06).
3. **Favoritos no DataStore Preferences:** Usei o `DataStore` salvando apenas a lista dos códigos (`IDs`) favoritados, já que o documento pedia para não usar banco de dados relacional como Room.

---

## 4. Trilha A — IA Aplicada à Otimização (Seção 10)

Para a Trilha A, foi criada uma segunda regra de classificação (`FleetPercentileClassifier.kt`) que olha para o comportamento do próprio grupo de aparelhos do arquivo, em vez de usar os limites travados em `7%/h` e `12%/h`:
- A regra calcula os percentis **P75 (9,77%/h)** para Atenção e **P90 (13,38%/h)** para Crítico em cima dos 28 aparelhos válidos da frota, além de analisar a taxa de *jank* separando pelo porte do processador (`tier`: low, mid, high).
- **Comparação na prática:** Na regra fixa, os aparelhos `DEV-1514` (dreno de `7,42%/h`) e `DEV-1625` (dreno de `7,58%/h`), que são dois Moto G13 (`tier: low`) sem nenhum *crash* e com *jank* baixo (< 3%), levam alerta de `ATENÇÃO` só porque passaram um pouco de 7%. Na regra por percentil da Trilha A, eles ficam como `OK`, pois esse consumo está dentro da média normal dos aparelhos da frota, evitando gerar alerta falso de manutenção.
- **Modelo híbrido com travas físicas (Guardrails):** O `FleetPercentileClassifier` implementa um modelo híbrido que combina os percentis empíricos ($P_{75}/P_{90}$ de dreno relativo + $P_{85}$ de *jank* por `tier`) com pisos e tetos físicos absolutos via `coerceIn` (`6,0%/h` a `10,5%/h` para Atenção e `10,0%/h` a `14,0%/h` para Crítico). Isso resolve a limitação clássica de modelos puramente estatísticos: evita falsos positivos em frotas muito saudáveis (piso) e impede falsos negativos ("falsos bons") caso a frota inteira sofra degradação simultânea (teto).

---

## 5. O Que Eu Faria em Seguida (Limitações e Melhorias)

1. **Separar o gasto de bateria de tela ligada e tela apagada:** Hoje a fórmula divide toda a energia gasta apenas pelos minutos de tela ligada. Se um celular gastar muita bateria em segundo plano com a tela apagada, a conta joga tudo nas costas do tempo de tela e pode gerar um alerta `CRÍTICO` injusto. O ideal seria ter esses dois consumos separados no JSON.
2. **Considerar a variação real da tensão e a temperatura:** Converter `mWh` dividindo por uma tensão fixa (ex: `3,85V`) é uma aproximação física, já que na prática a tensão de uma bateria de lítio varia conforme a carga cai.
3. **Estudar mais a fundo o Kotlin e o Android Studio:** Como este foi o meu primeiro contato real com desenvolvimento mobile, meu próximo passo é aprender melhor a base do Kotlin, do Jetpack Compose e como funcionam as ferramentas do Android Studio sem depender tanto de auxílio externo.
4. **Melhoria visual**: Deixar a aparência mais bonita e completa, adicionando imagens dos dispositivos.

---

## 6. Contexto de Desenvolvimento e Declaração de Uso de IA (Seção 11)

Seguindo a transparência pedida na Seção 11 do edital, quero deixar registrado de forma sincera como fiz este projeto:

- **Contexto e restrição de tempo:** Sou aluno do 6º período de Engenharia Elétrica e nunca tinha programado em Kotlin nem criado aplicativos no Android Studio. Além disso, tive pouco tempo disponível nesta semana por causa de uma prova bem pesada da faculdade.
- **Como utilizei a IA:** Por ser iniciante em programação mobile e estar com o prazo apertado, usei bastante o **Gemini** e suas ferramentas (Agy, Skills, etc.) para gerar os códigos em Kotlin e Jetpack Compose, montar os testes e configurar a integração contínua. Também usei a IA para revisar e organizar o texto deste `README.md` e do `PROTOCOLO.md`, corrigindo a gramática e deixando a explicação das minhas ideias mais clara.
- **Meu papel e o que aprendi:** Meu foco foi entender a lógica física e arquitetural do problema: por que converter `mWh` para `mAh` usando a tensão nominal ($Q = E/V$), por que barrar os aparelhos com zero no denominador como `INDETERMINADO` antes da conta, e como funciona o caminho dos dados saindo do JSON na pasta `data/`, passando pelo cálculo na `domain/` e indo para o `ViewModel` e para as telas na `ui/`. Também fiz toda a montagem do projeto no Android Studio, rodei os testes pelo terminal e testei o app funcionando direto no meu celular físico.