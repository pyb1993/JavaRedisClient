package Util;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class Logger {
    public static int logLevel = 0;// 只有级别大于一个级别之下的才会初夏
    public static BufferedWriter out;
    public static Executor tasks = Executors.newSingleThreadExecutor();
    static {
        try {
            File writename = new File("/usr/local/log/backend.log"); // 相对路径，如果没有则要建立一个新的output。txt文件
            writename.createNewFile(); // 创建新文件
            out = new BufferedWriter(new FileWriter(writename));
        }catch (Exception e){
            e.printStackTrace();
        }
    }
    public static void log(String msg){
        System.out.println(msg);
        try {
            out.write(msg + "\n"); // \r\n即为换行
            out.flush();
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    public static void debug(String msg){
        if(logLevel >= 1) {
            System.out.println(msg);
            try {
                out.write(msg + "\n"); // \r\n即为换行
                out.flush();
            }catch (Exception e){
                e.printStackTrace();
            }
        }
    }

    public static void show(String msg){
        return;
 /*       tasks.execute(()->{
            try {
                out.write(msg + "\n"); // \r\n即为换行
                out.flush();
            }catch (Exception e){
                e.printStackTrace();
            }
        });*/
    }



    static public void setDebug(){
        logLevel = 1;
    }
}
