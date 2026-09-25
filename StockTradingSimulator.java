import java.io.*;
import java.util.*;

/**
 * StockTradingSimulator
 * A console-based stock trading environment.
 *
 * Features:
 *  - Market data display with simulated price fluctuations
 *  - Buy / Sell operations with cash balance management
 *  - Portfolio performance tracking (holdings, P/L, history)
 *  - OOP design: Stock, User, Transaction, Market, Portfolio classes
 *  - File I/O persistence (portfolio saved to / loaded from a file)
 *
 * Compile: javac StockTradingSimulator.java
 * Run:     java StockTradingSimulator
 */
public class StockTradingSimulator {

    // ============================== STOCK ==============================
    static class Stock {
        private final String symbol;
        private final String name;
        private double price;
        private final double basePrice;   // reference price for % change
        private final Random rng = new Random();

        Stock(String symbol, String name, double price) {
            this.symbol = symbol;
            this.name = name;
            this.price = price;
            this.basePrice = price;
        }

        public String getSymbol() { return symbol; }
        public String getName()   { return name; }
        public double getPrice()  { return price; }

        /** Simulate a price tick: random walk between -4% and +4%. */
        public void tick() {
            double changePct = (rng.nextDouble() * 8.0) - 4.0; // -4% .. +4%
            price = Math.max(1.0, price * (1.0 + changePct / 100.0));
            price = Math.round(price * 100.0) / 100.0;
        }

        public double getChangePct() {
            return (price - basePrice) / basePrice * 100.0;
        }

        @Override
        public String toString() {
            return String.format("%-6s %-22s $%10.2f  %+6.2f%%",
                    symbol, name, price, getChangePct());
        }
    }

    // ========================== TRANSACTION ============================
    static class Transaction {
        enum Type { BUY, SELL }

        private final Type type;
        private final String symbol;
        private final int quantity;
        private final double pricePerShare;
        private final Date timestamp;

        Transaction(Type type, String symbol, int quantity, double pricePerShare) {
            this.type = type;
            this.symbol = symbol;
            this.quantity = quantity;
            this.pricePerShare = pricePerShare;
            this.timestamp = new Date();
        }

        public double getTotal() { return quantity * pricePerShare; }

        /** Serialize to a single line for file storage. */
        public String toFileString() {
            return type + "," + symbol + "," + quantity + "," + pricePerShare + "," + timestamp.getTime();
        }

        public static Transaction fromFileString(String line) {
            String[] p = line.split(",");
            Transaction t = new Transaction(
                    Type.valueOf(p[0]), p[1],
                    Integer.parseInt(p[2]), Double.parseDouble(p[3]));
            return t;
        }

        @Override
        public String toString() {
            return String.format("%-4s %-6s x%-5d @ $%10.2f  = $%12.2f   %s",
                    type, symbol, quantity, pricePerShare, getTotal(), timestamp);
        }
    }

    // =========================== PORTFOLIO =============================
    static class Portfolio {
        // symbol -> [totalQuantity, totalCostBasis]
        private final Map<String, double[]> holdings = new LinkedHashMap<>();
        private final List<Transaction> history = new ArrayList<>();

        public void recordBuy(String symbol, int qty, double price) {
            double[] h = holdings.getOrDefault(symbol, new double[]{0, 0});
            h[0] += qty;
            h[1] += qty * price;
            holdings.put(symbol, h);
            history.add(new Transaction(Transaction.Type.BUY, symbol, qty, price));
        }

        /** Returns true if the user owns at least qty shares. */
        public boolean canSell(String symbol, int qty) {
            double[] h = holdings.get(symbol);
            return h != null && h[0] >= qty;
        }

        public void recordSell(String symbol, int qty, double price) {
            double[] h = holdings.get(symbol);
            double avgCost = h[1] / h[0];
            h[0] -= qty;
            h[1] -= qty * avgCost;
            if (h[0] <= 0) holdings.remove(symbol);
            history.add(new Transaction(Transaction.Type.SELL, symbol, qty, price));
        }

        public int getQuantity(String symbol) {
            double[] h = holdings.get(symbol);
            return h == null ? 0 : (int) h[0];
        }

        public Map<String, double[]> getHoldings() { return holdings; }
        public List<Transaction> getHistory()      { return history; }

        /** Rebuild portfolio state from loaded transactions. */
        public void loadHistory(List<Transaction> txs) {
            holdings.clear();
            history.clear();
            for (Transaction t : txs) {
                if (t.type == Transaction.Type.BUY) {
                    double[] h = holdings.getOrDefault(t.symbol, new double[]{0, 0});
                    h[0] += t.quantity;
                    h[1] += t.quantity * t.pricePerShare;
                    holdings.put(t.symbol, h);
                } else {
                    double[] h = holdings.get(t.symbol);
                    if (h != null) {
                        double avg = h[1] / h[0];
                        h[0] -= t.quantity;
                        h[1] -= t.quantity * avg;
                        if (h[0] <= 0) holdings.remove(t.symbol);
                    }
                }
                history.add(t);
            }
        }
    }

    // ============================== USER ===============================
    static class User {
        private final String name;
        private double cash;
        private final double startingCash;
        private final Portfolio portfolio = new Portfolio();

        User(String name, double startingCash) {
            this.name = name;
            this.cash = startingCash;
            this.startingCash = startingCash;
        }

        public String getName()        { return name; }
        public double getCash()        { return cash; }
        public double getStartingCash(){ return startingCash; }
        public Portfolio getPortfolio(){ return portfolio; }

        public boolean buy(Stock stock, int qty) {
            double cost = qty * stock.getPrice();
            if (qty <= 0 || cost > cash) return false;
            cash -= cost;
            portfolio.recordBuy(stock.getSymbol(), qty, stock.getPrice());
            return true;
        }

        public boolean sell(Stock stock, int qty) {
            if (qty <= 0 || !portfolio.canSell(stock.getSymbol(), qty)) return false;
            cash += qty * stock.getPrice();
            portfolio.recordSell(stock.getSymbol(), qty, stock.getPrice());
            return true;
        }

        /** Total account value = cash + market value of holdings. */
        public double totalValue(Market market) {
            double v = cash;
            for (Map.Entry<String, double[]> e : portfolio.getHoldings().entrySet()) {
                Stock s = market.getStock(e.getKey());
                if (s != null) v += e.getValue()[0] * s.getPrice();
            }
            return v;
        }
    }

    // ============================= MARKET ==============================
    static class Market {
        private final Map<String, Stock> stocks = new LinkedHashMap<>();

        Market() {
            addStock(new Stock("AAPL", "Apple Inc.",          189.50));
            addStock(new Stock("GOOG", "Alphabet Inc.",       141.25));
            addStock(new Stock("MSFT", "Microsoft Corp.",     415.80));
            addStock(new Stock("AMZN", "Amazon.com Inc.",     178.35));
            addStock(new Stock("TSLA", "Tesla Inc.",          248.90));
            addStock(new Stock("NVDA", "NVIDIA Corp.",        875.40));
            addStock(new Stock("META", "Meta Platforms Inc.", 505.15));
            addStock(new Stock("NFLX", "Netflix Inc.",        628.70));
        }

        private void addStock(Stock s) { stocks.put(s.getSymbol(), s); }

        public Stock getStock(String symbol) { return stocks.get(symbol.toUpperCase()); }

        public Collection<Stock> getAllStocks() { return stocks.values(); }

        /** Advance the market: every stock price fluctuates. */
        public void tick() {
            for (Stock s : stocks.values()) s.tick();
        }

        public void display() {
            System.out.println("\n================ MARKET DATA ================");
            System.out.printf("%-6s %-22s %11s  %8s%n", "SYMBOL", "COMPANY", "PRICE", "CHANGE");
            System.out.println("---------------------------------------------");
            for (Stock s : stocks.values()) System.out.println(s);
            System.out.println("=============================================");
        }
    }

    // ========================= PERSISTENCE =============================
    private static final String SAVE_FILE = "portfolio_save.txt";

    private static void saveToFile(User user) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(SAVE_FILE))) {
            pw.println(user.getName());
            pw.println(user.getCash());
            pw.println(user.getStartingCash());
            for (Transaction t : user.getPortfolio().getHistory()) {
                pw.println(t.toFileString());
            }
            System.out.println("Portfolio saved to " + SAVE_FILE);
        } catch (IOException e) {
            System.out.println("Error saving portfolio: " + e.getMessage());
        }
    }

    private static User loadFromFile() {
        File f = new File(SAVE_FILE);
        if (!f.exists()) return null;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String name = br.readLine();
            double cash = Double.parseDouble(br.readLine());
            double starting = Double.parseDouble(br.readLine());
            User user = new User(name, starting);
            user.cash = cash;
            List<Transaction> txs = new ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.isBlank()) txs.add(Transaction.fromFileString(line));
            }
            user.getPortfolio().loadHistory(txs);
            System.out.println("Loaded saved portfolio for " + name + ".");
            return user;
        } catch (Exception e) {
            System.out.println("Could not load save file: " + e.getMessage());
            return null;
        }
    }

    // ========================== MAIN / UI ==============================
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        Market market = new Market();

        System.out.println("==============================================");
        System.out.println("      STOCK TRADING SIMULATOR                 ");
        System.out.println("==============================================");

        User user = loadFromFile();
        if (user == null) {
            System.out.print("Enter your name: ");
            String name = sc.nextLine().trim();
            if (name.isEmpty()) name = "Trader";
            System.out.print("Enter starting cash (default 10000): ");
            String cashIn = sc.nextLine().trim();
            double cash = 10000;
            try { if (!cashIn.isEmpty()) cash = Double.parseDouble(cashIn); }
            catch (NumberFormatException e) { System.out.println("Invalid amount, using $10,000."); }
            user = new User(name, cash);
        }

        boolean running = true;
        while (running) {
            System.out.println("\n---------------- MENU ----------------");
            System.out.println("1. View market data");
            System.out.println("2. Buy stock");
            System.out.println("3. Sell stock");
            System.out.println("4. View portfolio");
            System.out.println("5. View transaction history");
            System.out.println("6. Advance market (next day)");
            System.out.println("7. Save portfolio");
            System.out.println("8. Save & exit");
            System.out.print("Choose an option: ");

            String choice = sc.nextLine().trim();
            switch (choice) {
                case "1" -> market.display();
                case "2" -> {
                    market.display();
                    System.out.print("Enter symbol to buy: ");
                    Stock s = market.getStock(sc.nextLine().trim());
                    if (s == null) { System.out.println("Unknown symbol."); break; }
                    System.out.print("Quantity: ");
                    int qty = readInt(sc);
                    if (user.buy(s, qty)) {
                        System.out.printf("Bought %d x %s @ $%.2f (total $%.2f). Cash left: $%.2f%n",
                                qty, s.getSymbol(), s.getPrice(), qty * s.getPrice(), user.getCash());
                    } else {
                        System.out.println("Purchase failed: insufficient cash or invalid quantity.");
                    }
                }
                case "3" -> {
                    showPortfolio(user, market);
                    System.out.print("Enter symbol to sell: ");
                    Stock s = market.getStock(sc.nextLine().trim());
                    if (s == null) { System.out.println("Unknown symbol."); break; }
                    System.out.print("Quantity: ");
                    int qty = readInt(sc);
                    if (user.sell(s, qty)) {
                        System.out.printf("Sold %d x %s @ $%.2f (total $%.2f). Cash: $%.2f%n",
                                qty, s.getSymbol(), s.getPrice(), qty * s.getPrice(), user.getCash());
                    } else {
                        System.out.println("Sale failed: you don't own enough shares or invalid quantity.");
                    }
                }
                case "4" -> showPortfolio(user, market);
                case "5" -> {
                    System.out.println("\n========== TRANSACTION HISTORY ==========");
                    List<Transaction> hist = user.getPortfolio().getHistory();
                    if (hist.isEmpty()) System.out.println("No transactions yet.");
                    else hist.forEach(System.out::println);
                    System.out.println("=========================================");
                }
                case "6" -> {
                    market.tick();
                    System.out.println("Market advanced. Prices updated.");
                    market.display();
                }
                case "7" -> saveToFile(user);
                case "8" -> {
                    saveToFile(user);
                    System.out.println("Thanks for trading, " + user.getName() + "!");
                    running = false;
                }
                default -> System.out.println("Invalid option, try again.");
            }
        }
        sc.close();
    }

    private static int readInt(Scanner sc) {
        try { return Integer.parseInt(sc.nextLine().trim()); }
        catch (NumberFormatException e) { return -1; }
    }

    private static void showPortfolio(User user, Market market) {
        System.out.println("\n============== PORTFOLIO: " + user.getName() + " ==============");
        System.out.printf("Cash balance: $%.2f%n", user.getCash());
        System.out.printf("%-6s %-8s %-12s %-12s %-12s %-10s%n",
                "SYMBOL", "QTY", "AVG COST", "CUR PRICE", "VALUE", "P/L");
        System.out.println("---------------------------------------------------------------");
        double invested = 0, marketVal = 0;
        for (Map.Entry<String, double[]> e : user.getPortfolio().getHoldings().entrySet()) {
            String sym = e.getKey();
            int qty = (int) e.getValue()[0];
            double avgCost = e.getValue()[1] / qty;
            Stock s = market.getStock(sym);
            double cur = (s != null) ? s.getPrice() : avgCost;
            double value = qty * cur;
            double pl = value - qty * avgCost;
            invested += qty * avgCost;
            marketVal += value;
            System.out.printf("%-6s %-8d $%-11.2f $%-11.2f $%-11.2f %+.2f%n",
                    sym, qty, avgCost, cur, value, pl);
        }
        System.out.println("---------------------------------------------------------------");
        double total = user.totalValue(market);
        System.out.printf("Invested: $%.2f | Holdings value: $%.2f | Unrealized P/L: %+.2f%n",
                invested, marketVal, marketVal - invested);
        System.out.printf("TOTAL ACCOUNT VALUE: $%.2f (started with $%.2f, overall %+.2f%%)%n",
                total, user.getStartingCash(),
                (total - user.getStartingCash()) / user.getStartingCash() * 100.0);
        System.out.println("===============================================================");
    }
}
