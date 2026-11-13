package terralume;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/** Ponto de entrada. Opções: --world NOME --seed N --creative --dist N (e opções de captura para testes). */
public final class Main {
    public static void main(String[] args) throws Exception {
        if (relaunchOnMac(args)) return;
        System.setProperty("java.awt.headless", "true");
        Item.init();
        Game g = new Game();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--world" -> g.worldName = args[++i];
                case "--seed" -> {
                    String s = args[++i];
                    try { g.seedArg = Long.parseLong(s); } catch (NumberFormatException e) { g.seedArg = (long) s.hashCode(); }
                }
                case "--creative" -> g.creativeArg = true;
                case "--dist" -> g.renderDist = Math.max(3, Math.min(20, Integer.parseInt(args[++i])));
                case "--screenshot" -> g.screenshot = args[++i];
                case "--frames" -> g.frames = Integer.parseInt(args[++i]);
                case "--time" -> g.timeArg = Float.parseFloat(args[++i]);
                case "--look" -> { g.lookYaw = Float.parseFloat(args[++i]); g.lookPitch = Float.parseFloat(args[++i]); }
                case "--up" -> g.up = Integer.parseInt(args[++i]);
                case "--open" -> g.openScreen = args[++i];
                default -> System.err.println("Opção desconhecida: " + args[i]);
            }
        }
        g.run();
    }

    /** No macOS o GLFW exige a thread principal; relança a JVM com -XstartOnFirstThread se preciso. */
    private static boolean relaunchOnMac(String[] args) throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase().contains("mac")) return false;
        long pid = ProcessHandle.current().pid();
        if ("1".equals(System.getenv("JAVA_STARTED_ON_FIRST_THREAD_" + pid)) || System.getProperty("terralume.relaunched") != null) return false;
        List<String> cmd = new ArrayList<>();
        cmd.add(System.getProperty("java.home") + "/bin/java");
        cmd.add("-XstartOnFirstThread");
        cmd.add("-Dterralume.relaunched=1");
        cmd.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
        cmd.add("-cp"); cmd.add(System.getProperty("java.class.path"));
        cmd.add(Main.class.getName());
        cmd.addAll(List.of(args));
        new ProcessBuilder(cmd).inheritIO().start().waitFor();
        return true;
    }
}
