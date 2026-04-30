import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class Scratch {
    public static void main(String[] args) throws Exception {
        List<String> lines = Files.readAllLines(Paths.get("d:/gemini/minecraft_forge/v1.20.1_forge/gpu-load/build/createMcpToSrg/output.tsrg"), StandardCharsets.UTF_8);
        for (String line : lines) {
            if (line.contains("moveControl")) {
                System.out.println(line);
            }
        }
    }
}
