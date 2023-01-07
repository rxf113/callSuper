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
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;
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
            messager.printMessage(Diagnostic.Kind.WARNING,"Idea build feature currently not supported");
            return false;
        }

        if (!roundEnv.processingOver()) {

            messager.printMessage(Diagnostic.Kind.NOTE, "Begin MustCallSuperProcessor... , rootElements size: " + roundEnv.getRootElements().size());

            for (Element rootElement : roundEnv.getRootElements()) {

                TypeElement typeElement = (TypeElement) rootElement;

                if (typeElement.getKind() != ElementKind.CLASS) {
                    continue;
                }

                //1. 获取父类的有自定义MustCallSuper注解的方法
                List<ExecutableElement> methodWithCallSuperList = getSupClassMethodsWithCusAnnotation(typeElement);

                if (!methodWithCallSuperList.isEmpty()) {

                    String methodsStr = methodWithCallSuperList.stream().map(it -> it.getSimpleName().toString())
                            .collect(Collectors.joining());

                    messager.printMessage(Diagnostic.Kind.NOTE, "Current class: " + typeElement.getQualifiedName()
                            + " supClass with annotation methods: " + methodsStr);


                    //2. 获取当前类重写了父类方法的当前类方法
                    List<ExecutableElement> overrideMethodBySuperMethod = getOverrideMethodBySuperMethod(methodWithCallSuperList, typeElement);
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
                    if (!overrideMethodBySuperMethod.isEmpty()) {

                        String overrideMethodsStr = overrideMethodBySuperMethod.stream().map(it -> it.getSimpleName().toString())
                                .collect(Collectors.joining());

                        messager.printMessage(Diagnostic.Kind.NOTE, "Current class: " + typeElement.getQualifiedName()
                                + " override supClass methods: " + overrideMethodsStr);

                        //获取类源码
                        String classSourceCode = getClassSourceCode(typeElement);

                        for (ExecutableElement executableElement : overrideMethodBySuperMethod) {

                            //3. 判断源码中, 方法第一行是否有调用 super.xxxx
                            boolean b = checkFirstStatementCallSuper(classSourceCode, executableElement);

                            if (!b) {
                                //4. 校验不通过，抛异常
                                throw new MustCallSuperException("class: " + typeElement.getQualifiedName().toString()
                                        + " method: " + executableElement.getSimpleName().toString()
                                        + " 第一行没调用父类的此方法");
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * 根据当前元素 获取父类有自定义注解的方法
     *
     * @param typeElement
     * @return
     */
    private List<ExecutableElement> getSupClassMethodsWithCusAnnotation(TypeElement typeElement) {
        List<ExecutableElement> list = new ArrayList<>();
        TypeMirror superclass = typeElement.getSuperclass();
        TypeElement superclassElement = (TypeElement) types.asElement(superclass);
        List<? extends Element> enclosedElements = superclassElement.getEnclosedElements();
        for (Element element : enclosedElements) {
            if (element.getKind() == ElementKind.METHOD) {
                ExecutableElement method = (ExecutableElement) element;
                List<? extends AnnotationMirror> annotationMirrors = method.getAnnotationMirrors();
                for (AnnotationMirror annotationMirror : annotationMirrors) {
                    String annotationStr = annotationMirror.toString();
                    if (annotationStr.equals(CALL_SUPER_ANNOTATION_NAME)) {
                        list.add(method);
                    }
                }
            }
        }
        return list;
    }

    /**
     * 根据父类方法获取当前类重写了父类方法的当前类方法
     *
     * @param superMethods
     * @param typeElement
     * @return
     */
    private List<ExecutableElement> getOverrideMethodBySuperMethod(List<ExecutableElement> superMethods, TypeElement typeElement) {
        List<ExecutableElement> list = new ArrayList<>();

        List<? extends Element> enclosedElements = typeElement.getEnclosedElements();
        for (Element element : enclosedElements) {
            if (element.getKind() == ElementKind.METHOD) {
                ExecutableElement method = (ExecutableElement) element;
                Optional<ExecutableElement> first = superMethods.stream().filter(it -> isMethodSame(it, method)).findFirst();
                first.ifPresent(list::add);
            }
        }
        return list;
    }

    private boolean isMethodSame(ExecutableElement parentMethod, ExecutableElement sonMethod) {
        boolean b = parentMethod.getSimpleName().equals(sonMethod.getSimpleName()) &&
                parentMethod.getReturnType().equals(sonMethod.getReturnType());
        if (!b) {
            return false;
        }
//        parentMethod.getParameters().equals(sonMethod.getParameters())
        List<? extends VariableElement> paParameters = parentMethod.getParameters();
        List<? extends VariableElement> sonParameters = sonMethod.getParameters();
        if (paParameters.size() != sonParameters.size()) {
            return false;
        }
        if (paParameters.isEmpty()) {
            return true;
        }
        for (int i = 0; i < paParameters.size(); i++) {
            if (!paParameters.get(i).toString().equals(sonParameters.get(i).toString())) {
                return false;
            }
        }
        return true;
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
        NodeList<Parameter> parameters1 = jpMethod.getParameters();
        if (parameters1.size() != parameters.size()) {
            return false;
        }
        if (parameters1.isEmpty()) {
            return true;
        }
        for (int i = 0; i < parameters.size(); i++) {
            String pTypeStr = pMethod.getParameters().get(i).asType().toString();
            String jpTypeStr = jpMethod.getParameters().get(i).getType().toString();
            if (!pTypeStr.endsWith(jpTypeStr)) {
                //eg: pTypeStr == java.lang.Integer jpTYpeStr == Integer , 这里不一定完全正确，跟导包有关 todo
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
                //判断源码中方法第一行是否调用的super. 并且方法与当前 targetJpMethod 中的方法相同(方法名+参数名+参数个数+参数类型) //todo 参数类型暂时没实现好
                if ("super".equals(optionalExpression.get().toString())) {
                    //1. 判断方法名是否等于  targetJpMethod
                    String callMethodName = methodCall.getName().asString();
                    if (callMethodName.equals(targetJpMethod.getName().asString())) {
                        //2. 再判断 参数 是否等于  targetJpMethod
                        return checkMethodParametersEq(methodCall, targetJpMethod);
                    }
                }
            }
        }
        return false;

    }

    /**
     * 判断两个方法参数是否相同(长度 + 类型)
     *
     * @param methodCall
     * @param targetJpMethod
     * @return
     */
    private boolean checkMethodParametersEq(MethodCallExpr methodCall, MethodDeclaration targetJpMethod) {
        NodeList<Expression> arguments = methodCall.getArguments();
        NodeList<Parameter> parameters = targetJpMethod.getParameters();
        //先简但判断长度
        return arguments.size() == parameters.size();
        //todo 类型匹配
//        if (arguments.size() != parameters.size()) {
//            return false;
//        }
//        if (arguments.isEmpty()) {
//            return true;
//        }
//        for (int i = 0; i < arguments.size(); i++) {
//            ResolvedType resolvedType = symbolSolver.calculateType(arguments.get(i));
//            String argTypeStr = null;
//            if (resolvedType instanceof ReferenceTypeImpl){
//                argTypeStr = ((ReferenceTypeImpl) resolvedType).getTypeDeclaration().get().getClassName();
//            }else if (resolvedType instanceof ResolvedPrimitiveType){
//                argTypeStr = ((ResolvedPrimitiveType) resolvedType).getBoxTypeQName();
//            }
//
//            String paTypeStr = parameters.get(i).getType().toString();
//            if (!paTypeStr.equals(argTypeStr)) {
//                return false;
//            }
//        }
//        return true;
    }
}