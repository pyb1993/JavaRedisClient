package RedisClient;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/* 相应注册中心,用来实现 type => response 的映射
*  1 客户端是线程安全的,所以这里保证支持多个线程(ConcurrentHashMap)
* */
public class ResponseRegister {
    private static Map<String, Class<?>> clazzes = new ConcurrentHashMap<>();
    public  static void register(String type, Class<?> clazz) {
        clazzes.put(type, clazz);
    }
    public  static Class<?> get(String type) {
        return clazzes.get(type);
    }
}

