package com.rxf113;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author rxf113
 */
@SupportedAnnotationTypes(value = "*")
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class MustCallSuperProcessor extends AbstractProcessor {

    private static final String CALL_SUPER_ANNOTATION_NAME = "@com.rxf113.MustCallSuper";
    private Types types;

    private JavaParser javaParser;

    private static final String FILE_SEPARATOR = File.separator;

    private Messager messager;

    private static final Pattern CONTENT_IN_PARENTHESES_PATTERN = Pattern.compile("\\((.*?)\\)");

    /**
     * key: 全限类名，value: 类的所有方法。
     * 目的：缓存类及对应方法，提高效率
     */
    private static final Map<String, List<ExecutableElement>> QUALIFIED_NAME_2_METHODS_CACHE_MAP = new HashMap<>(64, 1);

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);

        this.javaParser = new JavaParser();
        this.messager = processingEnv.getMessager();
        this.types = processingEnv.getTypeUtils();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        //idea build时，获取项目真实路径错误的问题
        //描述: idea build 时，获取到的当前项目路径为  ...\AppData\Local\JetBrains\IntelliJIdea2022.2\compile-server\.
        //todo 暂时跳过 idea build 的场景
        String projectPath = System.getProperty("user.dir");
        if (projectPath.matches(".*JetBrains.*IntelliJIdea.*compile-server")) {
            messager.printMessage(Diagnostic.Kind.WARNING, "Idea build feature currently not supported");
            return false;
        }

        if (!roundEnv.processingOver()) {

            messager.printMessage(Diagnostic.Kind.NOTE, "Begin MustCallSuperProcessor... , rootElements size: " + roundEnv.getRootElements().size());

            for (Element rootElement : roundEnv.getRootElements()) {

                TypeElement typeElement = (TypeElement) rootElement;

                if (typeElement.getKind() != ElementKind.CLASS) {
                    continue;
                }

                messager.printMessage(Diagnostic.Kind.NOTE, "Current class: " + typeElement.getQualifiedName());

                //递归获取当前类中重写的，并且在父类中有自定义注解 com.rxf113.MustCallSuper 的方法
                List<ExecutableElement> methodsWithCusAnnotation = getOverrideMethodsWithCusAnnotationOfCuClass(typeElement);

                //如果有重写父类的方法，校验这个方法第一行是否有调用 super.xxxx

                    /*
                     校验步骤:
                     方案1: 获取类源码，然后通过javaParser等工具解析源码得到方法第一行代码，判断是否是super.这个方法
                     方案2: 由于此时还未将编译的class写入target目录，可以手动编译，然后asm解析class,判断方法的第一行代码是否是super.这个方法
                     方案1解析简单，但是判断麻烦，涉及到参数个数以及类型的匹配
                     方案2编译解析麻烦，但是判断简单而且准确
                     我先用方案2尝试了一下，在类没有其它依赖的情况下 效果不错，但是类依赖其它类，编译就比较麻烦
                     最后这个例子还是选择方案1实现，但是如类型判断 还有些不严谨
                     */
                if (!methodsWithCusAnnotation.isEmpty()) {

                    String overrideMethodsStr = methodsWithCusAnnotation.stream().map(it -> it.getSimpleName().toString())
                            .collect(Collectors.joining());

                    messager.printMessage(Diagnostic.Kind.NOTE, "Current class: " + typeElement.getQualifiedName()
                            + " override supClass methods: " + overrideMethodsStr);

                    //获取类源码
                    String classSourceCode = getClassSourceCode(typeElement);

                    for (ExecutableElement executableElement : methodsWithCusAnnotation) {

                        //3. 判断源码中, 方法第一行是否有调用 super.xxxx
                        boolean b = checkFirstStatementCallSuper(classSourceCode, executableElement);

                        if (!b) {
                            //4. 校验不通过，抛异常
                            throw new MustCallSuperException("class: " + "[" + typeElement.getQualifiedName().toString() + "],"
                                    + " method: " + "[" + executableElement.getSimpleName().toString() + "]"
                                    + " 第一行没调用父类的此方法");
                        }
                    }
                }
            }
        }
        return false;
    }


    /**
     * 获取当前类中，重写过并且有自定义MustCallSuper注解的方法
     *
     * @param cuElement
     * @return
     */
    private List<ExecutableElement> getOverrideMethodsWithCusAnnotationOfCuClass(TypeElement cuElement) {
        List<ExecutableElement> cuMethods = cuElement.getEnclosedElements()
                .stream().filter(it -> it.getKind() == ElementKind.METHOD)
                .map(ExecutableElement.class::cast)
                .collect(Collectors.toList());
        TypeMirror superclass = cuElement.getSuperclass();
        TypeElement parentElement = (TypeElement) types.asElement(superclass);
        return getMethodsByParentRecursive(cuMethods, cuElement, parentElement, new ArrayList<>());
    }

    /**
     * 通过父类，递归获取满足条件的当前类方法
     *
     * @param targetClaMethods 目标子类的方法(固定的)
     * @param targetElement    目标子类(固定的)
     * @param parentElement    父类(递归向上)
     * @param resultMethods    结果集
     * @return
     */
    private List<ExecutableElement> getMethodsByParentRecursive(List<ExecutableElement> targetClaMethods,
                                                                TypeElement targetElement,
                                                                TypeElement parentElement,
                                                                List<ExecutableElement> resultMethods) {
        Elements utils = processingEnv.getElementUtils();

        String objName = Object.class.getName();
        //当目标子类方法都被查找过，或者已经遍历到 java.lang.Object 中止递归
        if (targetClaMethods.isEmpty() || parentElement.getQualifiedName().toString().equals(objName)) {
            return resultMethods;
        }

        List<ExecutableElement> parentMethods = QUALIFIED_NAME_2_METHODS_CACHE_MAP.get(parentElement.getQualifiedName().toString());
        if (parentMethods == null) {
            //获取父类中，有自定义注解 com.rxf113.MustCallSuper 的非抽象方法
            parentMethods = parentElement.getEnclosedElements()
                    .stream()
                    .filter(it -> it.getKind() == ElementKind.METHOD
                            && !it.getModifiers().contains(Modifier.ABSTRACT))
                    //有自定义注解
                    .filter(it -> it.getAnnotationMirrors().stream()
                            .anyMatch(annotation -> annotation.toString().equals(CALL_SUPER_ANNOTATION_NAME)))
                    .map(ExecutableElement.class::cast).collect(Collectors.toList());

            QUALIFIED_NAME_2_METHODS_CACHE_MAP.put(parentElement.getQualifiedName().toString(), parentMethods);
        }


        Iterator<ExecutableElement> iterator = targetClaMethods.iterator();
        while (iterator.hasNext()) {
            ExecutableElement targetClaMethod = iterator.next();
            for (ExecutableElement parentMethod : parentMethods) {
                if (utils.overrides(targetClaMethod, parentMethod, targetElement)) {
                    //满足条件的方法，加入结果集
                    resultMethods.add(targetClaMethod);
                    //并移除此方法
                    iterator.remove();
                }
            }
        }

        //递归查找父类
        TypeMirror superclass = parentElement.getSuperclass();
        parentElement = (TypeElement) types.asElement(superclass);
        return getMethodsByParentRecursive(targetClaMethods, targetElement, parentElement, resultMethods);
    }

    /**
     * 校验源码解析出来的方法和processor里的方法是否一样
     *
     * @param pMethod
     * @param jpMethod
     * @return
     */
    private boolean isSameMethod(ExecutableElement pMethod, MethodDeclaration jpMethod) {
        boolean b = pMethod.getSimpleName().toString().equals(jpMethod.getNameAsString()) &&
                pMethod.getReturnType().toString().equals(jpMethod.getType().asString());
        if (!b) {
            return false;
        }
        List<? extends VariableElement> parameters = pMethod.getParameters();
        NodeList<Parameter> jpParameters = jpMethod.getParameters();
        if (jpParameters.size() != parameters.size()) {
            return false;
        }
        if (jpParameters.isEmpty()) {
            return true;
        }
        for (int i = 0; i < parameters.size(); i++) {
            String pTypeStr = pMethod.getParameters().get(i).asType().toString();
            String jpTypeStr = jpMethod.getParameters().get(i).getType().toString();
            if (!pTypeStr.endsWith(jpTypeStr)) {
                //eg: pTypeStr == java.lang.Integer jpTYpeStr == Integer , 这里不一定完全正确，跟导包有关
                //例如 pTypeStr == com.rxf113.Integer, jpTYpeStr == Integer 判断是正确的，但实际不正确 todo
                return false;
            }
        }
        return true;

    }

    private final Map<String, String> cacheClassName2SourceCode = new HashMap<>(16, 1);

    private String getClassSourceCode(TypeElement typeElement) {
        String qualifiedClassName = typeElement.getQualifiedName().toString();
        //先从缓存中取
        String sourceCode = cacheClassName2SourceCode.get(qualifiedClassName);
        if (sourceCode != null) {
            return sourceCode;
        }
        String className = qualifiedClassName.replace(".", FILE_SEPARATOR);
        String realFilePath = System.getProperty("user.dir") +
                FILE_SEPARATOR + "src" + FILE_SEPARATOR
                + FILE_SEPARATOR + "main" + FILE_SEPARATOR +
                FILE_SEPARATOR + "java" + FILE_SEPARATOR + className + ".java";
        StringBuilder sb = new StringBuilder();
        String line;
        try (BufferedReader reader = new BufferedReader(new FileReader(realFilePath))) {
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (IOException e) {
            throw new MustCallSuperException("获取类源码失败,", e);
        }

        sourceCode = sb.toString();
        cacheClassName2SourceCode.put(qualifiedClassName, sourceCode);
        sb.append(sourceCode);
        return sourceCode;
    }

    /**
     * 校验第一语句是否是调用的super.xx
     *
     * @param sourceCode
     * @param pMethod
     * @return
     */
    private boolean checkFirstStatementCallSuper(String sourceCode, ExecutableElement pMethod) {

        List<MethodDeclaration> methods;
        try {
            ParseResult<CompilationUnit> parseResult = javaParser.parse(sourceCode);
            CompilationUnit compilationUnit = parseResult.getResult().get();
            List<TypeDeclaration<?>> claTypes = compilationUnit.getTypes();
            TypeDeclaration<?> type = claTypes.get(0);
            methods = type.getMethods();
        } catch (Exception e) {
            throw new MustCallSuperException("解析源码出错", e);
        }
        MethodDeclaration targetJpMethod = null;
        for (MethodDeclaration method : methods) {
            if (isSameMethod(pMethod, method)) {
                targetJpMethod = method;
                break;
            }
        }
        if (targetJpMethod == null) {
            return false;
        }
        Optional<BlockStmt> stmtOptional = targetJpMethod.getBody();
        if (!stmtOptional.isPresent()) {
            return false;
        }
        BlockStmt body = stmtOptional.get();

        List<Statement> statements = body.getStatements();
        if (statements.isEmpty()) {
            return false;
        }
        //当前方法第一段代码
        Statement firstStatement = statements.get(0);

        if (firstStatement instanceof ExpressionStmt) {
            ExpressionStmt expressionStatement = (ExpressionStmt) firstStatement;
            if (expressionStatement.getExpression() instanceof MethodCallExpr) {
                MethodCallExpr methodCall = (MethodCallExpr) expressionStatement.getExpression();
                Optional<Expression> optionalExpression = methodCall.getScope();
                if (!optionalExpression.isPresent()) {
                    return false;
                }
                if ("super".equals(optionalExpression.get().toString())) {
                    //方法名相等，并且方法的参数名也相等，认为是调用的父类方法
                    //例如以下: 首先判断 testMethod == testMethod， 然后判断 a, b == a, b
                    //    public void testMethod(Integer a, String b) {
                    //        super.testMethod(a, b);
                    //    }

                    //判断方法名是否等于  targetJpMethod
                    String callMethodName = methodCall.getName().asString();
                    if (callMethodName.equals(targetJpMethod.getName().asString())) {
                        //再判断 参数名 是否等于  targetJpMethod

                        //提取 methodCall 参数名
                        Matcher matcher = CONTENT_IN_PARENTHESES_PATTERN.matcher(methodCall.toString());
                        if (matcher.find()) {
                            String methodCallParamName = matcher.group(1);

                            //提取 targetJpMethod 参数名
                            String targetJpMethodParamName = targetJpMethod.getParameters()
                                    .stream()
                                    .map(it -> it.getName().toString())
                                    .collect(Collectors.joining(", "));

                            return methodCallParamName.equals(targetJpMethodParamName);
                        }
                    }
                }
            }
        }
        return false;
    }
}
