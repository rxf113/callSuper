import org.junit.Test;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试类，运行前提，也需要先清空spi文件，然后编译项目，之后再跑test
 * 功能: 手动编译java文件，触发 com.rxf113.MustCallSuperProcessor 中的 process 方法
 */
public class MustCallSuperTest {

    @Test
    public void manualCompileJavaFile() {
        File file = new File(System.getProperty("user.dir") + "/src/main/java/com/test/TestSon.java");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            // 获取文件
            Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjects(file);
            // 编译参数
            List<String> options = new ArrayList<>();
            //指定我自己的processor
            options.add("-processor");
            options.add("com.rxf113.MustCallSuperProcessor");
            compiler.getTask(null, fileManager, null, options, null, compilationUnits).call();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
