## callSuper

### 描述

- 一个类似安卓的 CallSuper 注解功能的 javac 编译期的检测程序。

- 自定义了一个 MustCallSuper 注解，父类方法加上此注解，子类若要重写这个方法，必须在第一行调用super.这个方法

### 举例子

```java

import com.rxf113.MustCallSuper;

public class Parent {

    @MustCallSuper
    void close() {
        //假如这行代码是必须被调用的
        System.out.println("Parent close");
    }
}

public class Sun extends Parent {
    @Override
    void close() {
        super.close(); //如果子类没有这行代码，编译将会报错
        System.out.println("Sun close");
    }
}


```

### 简单使用

#### 1. 将此项目打包

1.1 **清空 resources 里的 spi 文件里的信息**，再完成编译。 (要先清空spi文件信息，直接编译会找不到这个类文件)

1.2 将 resources 里的 spi 文件里的信息补全，然后 mvn package 打包并 install 到本地 maven 仓库

#### 2. 在自己项目里引入此包依赖

例如:

```xml

<dependency>
    <groupId>com.rxf113</groupId>
    <artifactId>callSuper</artifactId>
    <version>1.0</version>
</dependency>
```

2.1 在有需要的父类方法上加上 com.rxf113.MustCallSuper 注解

例如:

```java
public abstract class Parent {
    @MustCallSuper
    public void testMethod() {
        System.out.println("super testMethod must be invoked");
    }
}
```

2.2 **如果是 maven 项目，在 pom文件中加入 plugin:**

```xml
<plugin>
    <groupId>org.bsc.maven</groupId>
    <artifactId>maven-processor-plugin</artifactId>
    <executions>
        <execution>
            <id>process</id>
            <goals>
                <goal>process</goal>
            </goals>
            <phase>generate-sources</phase>
            <configuration>
                <processors>
                    <processor>com.rxf113.MustCallSuperProcessor</processor>
                </processors>
            </configuration>
        </execution>
    </executions>
</plugin>
```

#### 3. 执行 mvn clean compile 编译项目，就能执行检查

## 问题

1. 当前项目是只支持java8

> 解决: 可自信根据jdk版本调整，修改 com.rxf113.MustCallSuperProcessor 的 SupportedSourceVersion 注解的值即可

2. 只支持 src/main/java 这种标准目录结构的项目

> 解决: (可以修改 com.rxf113.MustCallSuperProcessor 的 getClassSourceCode 方法中的 filePath 前缀)

3. 对于 idea build 操作，目前不支持在 build 过程中完成检查
- 等待解决中...

## 备注

- 大体提供一个 javac 编译期校验的框架，具体实现可能还不够完善, 可自行补充。
- 想基于 javac 做其它拓展也可参考自行改造
