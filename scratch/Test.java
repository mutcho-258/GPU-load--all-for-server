package scratch;
import java.lang.reflect.Field;
public class Test {
    public static void main(String[] args) {
        try {
            Class<?> clazz = Class.forName("net.minecraft.world.level.biome.MultiNoiseBiomeSource");
            for (Field f : clazz.getDeclaredFields()) {
                System.out.println(f.getName() + " : " + f.getType().getName());
            }
        } catch (Exception e) { e.printStackTrace(); }
    }
}
