package main.visitor.codeGenerator;

import main.ast.nodes.Program;
import main.ast.nodes.declaration.classDec.ClassDeclaration;
import main.ast.nodes.declaration.classDec.classMembersDec.ConstructorDeclaration;
import main.ast.nodes.declaration.classDec.classMembersDec.FieldDeclaration;
import main.ast.nodes.declaration.classDec.classMembersDec.MethodDeclaration;
import main.ast.nodes.declaration.variableDec.VarDeclaration;
import main.ast.nodes.expression.*;
import main.ast.nodes.expression.operators.BinaryOperator;
import main.ast.nodes.expression.operators.UnaryOperator;
import main.ast.nodes.expression.values.ListValue;
import main.ast.nodes.expression.values.NullValue;
import main.ast.nodes.expression.values.primitive.BoolValue;
import main.ast.nodes.expression.values.primitive.IntValue;
import main.ast.nodes.expression.values.primitive.StringValue;
import main.ast.nodes.statement.*;
import main.ast.nodes.statement.loop.BreakStmt;
import main.ast.nodes.statement.loop.ContinueStmt;
import main.ast.nodes.statement.loop.ForStmt;
import main.ast.nodes.statement.loop.ForeachStmt;
import main.ast.types.NullType;
import main.ast.types.Type;
import main.ast.types.functionPointer.FptrType;
import main.ast.types.list.ListNameType;
import main.ast.types.list.ListType;
import main.ast.types.single.BoolType;
import main.ast.types.single.ClassType;
import main.ast.types.single.IntType;
import main.ast.types.single.StringType;
import main.symbolTable.SymbolTable;
import main.symbolTable.exceptions.ItemNotFoundException;
import main.symbolTable.items.ClassSymbolTableItem;
import main.symbolTable.items.FieldSymbolTableItem;
import main.symbolTable.utils.graph.Graph;
import main.symbolTable.utils.stack.Stack;
import main.visitor.Visitor;
import main.visitor.typeChecker.ExpressionTypeChecker;

import java.io.*;
import java.util.ArrayList;

public class CodeGenerator extends Visitor<String> {
    ExpressionTypeChecker expressionTypeChecker;
    Graph<String> classHierarchy;
    private String outputPath;
    private FileWriter currentFile;
    private ClassDeclaration currentClass;
    private MethodDeclaration currentMethod;
    private int globalCounter;
    private Stack<String> breakLabelStack;
    private Stack<String> continueLabelStack;
    private int tempSlotInCurrentMethod;

    public CodeGenerator(Graph<String> classHierarchy) {
        this.classHierarchy = classHierarchy;
        this.expressionTypeChecker = new ExpressionTypeChecker(classHierarchy);
        this.prepareOutputFolder();
        this.globalCounter = 0;
        this.breakLabelStack = new Stack<>();
        this.continueLabelStack = new Stack<>();
        this.tempSlotInCurrentMethod = 0;
    }

    private void prepareOutputFolder() {
        this.outputPath = "output/";
        String jasminPath = "utilities/jarFiles/jasmin.jar";
        String listClassPath = "utilities/codeGenerationUtilityClasses/List.j";
        String fptrClassPath = "utilities/codeGenerationUtilityClasses/Fptr.j";
        try{
            File directory = new File(this.outputPath);
            File[] files = directory.listFiles();
            if(files != null)
                for (File file : files)
                    file.delete();
            directory.mkdir();
        }
        catch(SecurityException e) { }
        copyFile(jasminPath, this.outputPath + "jasmin.jar");
        copyFile(listClassPath, this.outputPath + "List.j");
        copyFile(fptrClassPath, this.outputPath + "Fptr.j");
    }

    private void copyFile(String toBeCopied, String toBePasted) {
        try {
            File readingFile = new File(toBeCopied);
            File writingFile = new File(toBePasted);
            InputStream readingFileStream = new FileInputStream(readingFile);
            OutputStream writingFileStream = new FileOutputStream(writingFile);
            byte[] buffer = new byte[1024];
            int readLength;
            while ((readLength = readingFileStream.read(buffer)) > 0)
                writingFileStream.write(buffer, 0, readLength);
            readingFileStream.close();
            writingFileStream.close();
        } catch (IOException e) { }
    }

    private void createFile(String name) {
        try {
            String path = this.outputPath + name + ".j";
            File file = new File(path);
            file.createNewFile();
            FileWriter fileWriter = new FileWriter(path);
            this.currentFile = fileWriter;
        } catch (IOException e) {}
    }

    private void addCommand(String command) {
        try {
            command = String.join("\n\t\t", command.split("\n"));
            if(command.startsWith("Label_"))
                this.currentFile.write("\t" + command + "\n");
            else if(command.startsWith("."))
                this.currentFile.write(command + "\n");
            else
                this.currentFile.write("\t\t" + command + "\n");
            this.currentFile.flush();
        } catch (IOException e) {}
    }

    private String getNewLabel() {
        return Integer.toString(globalCounter ++);
    }

    private String getObjectType(Type t) {
        if (t instanceof IntType)
            return "java/lang/Integer";
        else if (t instanceof ListType) //todo: is it ok?
            return "List";
        else if (t instanceof BoolType)
            return "java/lang/Boolean";
        else if (t instanceof StringType)
            return "java/lang/String";
        else if (t instanceof ClassType)
            return ((ClassType) t).getClassName().getName();
        else if (t instanceof FptrType)
            return "Fptr";
        else
            return "ERROR Type";
    }

    // Sophia Type -> Java Object
    private String makeTypeSignature(Type t) {
        return String.format("L%s;", getObjectType(t));
    }

    // Sophia Type -> Java Primitive
    private String getPrimitiveType(Type t) {
        if (t instanceof IntType)
            return "I";
        else if (t instanceof BoolType)
            return "Z";
        else
            return makeTypeSignature(t);
    }

    private String convertJavaObjToPrimitive(Type t) {
        if (t instanceof IntType) {
            return "invokevirtual java/lang/Integer/intValue()I";
        } else if (t instanceof BoolType) {
            return "invokevirtual java/lang/Boolean/booleanValue()Z";
        }
        return "";
    }

    //convert primitive to Java Object
    private String ConvertPrimitiveToJavaObj(Type t) {
        if (t instanceof IntType) {
            return "invokestatic java/lang/Integer/valueOf(I)Ljava/lang/Integer;";
        } else if (t instanceof BoolType) {
            return "invokestatic java/lang/Boolean/valueOf(Z)Ljava/lang/Boolean;";
        }
        return "";
    }

    private String castObject(Type t) {
        return String.format("checkcast %s\n", getObjectType(t));
    }

    private String makeReturnTypeSignature(MethodDeclaration methodDeclaration) {
        if (methodDeclaration.getReturnType() instanceof NullType) {
            return "V";
        } else {
            return makeTypeSignature(methodDeclaration.getReturnType());
        }
    }

    private String getNewArrayList() {
        String commands = "";
        commands += "new java/util/ArrayList\n";
        commands += "dup\n";
        commands += "invokespecial java/util/ArrayList/<init>()V\n";
        return commands;
    }

    private String getNewList() {
        String commands = "";
        commands += "new List\n";
        commands += "dup\n";
        return commands;
    }

    private void putInitValueList(ListType varType) {
        addCommand(getNewArrayList());
        int tempSlot = slotOf("");
        addCommand(String.format("astore %d\n", tempSlot));
        ArrayList<ListNameType> listElements = varType.getElementsTypes();
        for (ListNameType listElement : listElements) {
            addCommand(String.format("aload %d", tempSlot));
            addCommand(";--- recursion call");
            putInitValue(listElement.getType());
            addCommand("invokevirtual java/util/ArrayList/add(Ljava/lang/Object;)Z");
            addCommand("pop");
        }
        addCommand(getNewList());
        addCommand(String.format("aload %d", tempSlot));
        addCommand("invokespecial List/<init>(Ljava/util/ArrayList;)V");
    }

    private void putInitValue(Type varType) {
        addCommand("; --- init values ---");
        if (varType instanceof IntType) {
            addCommand("ldc 0");
            addCommand(ConvertPrimitiveToJavaObj(varType));
        } else if (varType instanceof BoolType) {
            addCommand("ldc 0");
            addCommand(ConvertPrimitiveToJavaObj(varType));
        } else if (varType instanceof StringType) {
            addCommand("ldc \"\"");
        } else if (varType instanceof FptrType || varType instanceof ClassType) {
            addCommand("aconst_null");
        } else if (varType instanceof ListType) {
            putInitValueList((ListType) varType);
        }
        addCommand("");
        addCommand("");
    }

    private void addStackLocalSize() {
        addCommand(".limit stack 128");
        addCommand(".limit locals 128");
    }

    private void callParentConstructor() {
        addCommand(String.format("invokespecial %s/<init>()V", getClassParentName(currentClass)));
    }

    private void initializeFields() {
        for (FieldDeclaration fieldDeclaration : currentClass.getFields()) {
            VarDeclaration varDec = fieldDeclaration.getVarDeclaration();
            addCommand("aload 0");
            addCommand(";--- initializeFields call");
            putInitValue(varDec.getType());
            addCommand(
                    String.format(
                            "putfield %s/%s %s",
                            currentClass.getClassName().getName(),
                            varDec.getVarName().getName(),
                            makeTypeSignature(varDec.getType())
                    )
            );
        }
    }

    private void addDefaultConstructor() {
        addCommand(".method public <init>()V");
        addStackLocalSize();
        addCommand(";in addDefaultConstructor");
        addCommand("aload 0");
        callParentConstructor();
        initializeFields();
        addCommand("return");
        addCommand(".end method");
    }

    private void addStaticMainMethod() {
        addCommand(".method public static main([Ljava/lang/String;)V");
        addStackLocalSize();
        addCommand(String.format("new %s", "Main"));
        addCommand(String.format("invokespecial %s/<init>()V", "Main"));
        addCommand("return");
        addCommand(".end method");
    }

    private int slotOf(String identifier) {
        int index = 1;
        for (VarDeclaration arg: currentMethod.getArgs()) {
            if (arg.getVarName().getName().equals(identifier)) {
                return index;
            }
            index ++;
        }

        for (VarDeclaration localVar: currentMethod.getLocalVars()) {
            if (localVar.getVarName().getName().equals(identifier)) {
                return index;
            }
            index ++;
        }

        if (identifier.equals("-1")) {
            return tempSlotInCurrentMethod + index;
        }

        if (identifier.equals("")) {
            this.tempSlotInCurrentMethod ++;
            return tempSlotInCurrentMethod + index;
        }
        return index;
    }

    private String getClassParentName(ClassDeclaration classDeclaration) {
        if (classDeclaration.getParentClassName() != null) {
            return  classDeclaration.getParentClassName().getName();
        }
        return "java/lang/Object";
    }

    private Boolean isClassMain(ClassDeclaration classDeclaration) {
        return classDeclaration.getClassName().getName().equals("Main");
    }

    private void methodBodyVisitor(MethodDeclaration methodDeclaration) {
        for (VarDeclaration localVar: methodDeclaration.getLocalVars()) {
            localVar.accept(this);
        }

        for (Statement statement: methodDeclaration.getBody()) {
            statement.accept(this);

        }

        if (!methodDeclaration.getDoesReturn()) {
            addCommand("return");
        }

        addCommand(".end method");
    }

    private String shortCircuit(BinaryExpression binaryExpression) {
        String commands = "";
        String scopeLabel = getNewLabel();
        String jumpExpression = String.format("jumpExpression_%s", scopeLabel);
        String endExpression = String.format("endExpression_%s", scopeLabel);

        Expression firstOperand = binaryExpression.getFirstOperand();
        Expression secondOperand = binaryExpression.getSecondOperand();
        BinaryOperator binaryOperator = binaryExpression.getBinaryOperator();

        commands += String.format("%s\n", firstOperand.accept(this));
        if (binaryOperator == BinaryOperator.and) {
            commands += String.format("ifeq %s\n", jumpExpression);
        } else { // BinaryOperator.or
            commands += String.format("ifne %s\n", jumpExpression);
        }

        commands += String.format("%s\n", secondOperand.accept(this));
        commands += String.format("goto %s\n", endExpression);

        commands += String.format("%s:\n", jumpExpression);
        if (binaryOperator == BinaryOperator.and) {
            commands += "iconst_0\n";
        } else { // BinaryOperator.or
            commands += "iconst_1\n";
        }

        commands += String.format("%s:\n", endExpression);

        return commands;
    }

    private String compareExpressions(String operator, String cmd) {
        String scopeLabel = getNewLabel();
        String commands = "";
        String operatorBegin = String.format("%s_%s", operator, scopeLabel);
        String operatorEnd = String.format("end_%s_%s", operator, scopeLabel);
        String expEnd = String.format("end_exp_%s", scopeLabel);

        commands += String.format("if_%s%s %s\n", cmd, operator, operatorEnd);
        commands += String.format("%s:\n", operatorBegin);
        commands += "iconst_0\n";
        commands += String.format("goto %s\n", expEnd);
        commands += String.format("%s:\n", operatorEnd);
        commands += "iconst_1\n";
        commands += String.format("%s:\n", expEnd);

        return commands;
    }

    private String equalityExpressions(Expression expression, String operator) {
        Type expType = expression.accept(expressionTypeChecker);
        if (expType instanceof IntType || expType instanceof BoolType) {
            return compareExpressions(operator, "icmp");
        }
        return compareExpressions(operator, "acmp");
    }

    @Override
    public String visit(Program program) {
        for (ClassDeclaration classDeclaration : program.getClasses()) {
            this.expressionTypeChecker.setCurrentClass(classDeclaration);
            this.currentClass = classDeclaration;
            classDeclaration.accept(this);
        }
        return null;
    }

    @Override
    public String visit(ClassDeclaration classDeclaration) {
        String className = classDeclaration.getClassName().getName();
        createFile(className);

        addCommand(String.format(".class public %s", className));
        addCommand(String.format(".super %s", getClassParentName(classDeclaration)));
        addCommand("");
        addCommand("");
        for (FieldDeclaration fieldDeclaration : classDeclaration.getFields()) {
            fieldDeclaration.accept(this);
        }

        if (classDeclaration.getConstructor() != null) {
            this.expressionTypeChecker.setCurrentMethod(classDeclaration.getConstructor());
            this.currentMethod = classDeclaration.getConstructor();
            this.tempSlotInCurrentMethod = 0;
            classDeclaration.getConstructor().accept(this);
        } else {
            addDefaultConstructor();
        }

        for (MethodDeclaration methodDeclaration : classDeclaration.getMethods()) {
            this.expressionTypeChecker.setCurrentMethod(methodDeclaration);
            this.currentMethod = methodDeclaration;
            this.tempSlotInCurrentMethod = 0;
            methodDeclaration.accept(this);
        }

        return null;
    }

    @Override
    public String visit(ConstructorDeclaration constructorDeclaration) {
        if (constructorDeclaration.getArgs().size() > 0) {
            addDefaultConstructor();
        }

        if (isClassMain(currentClass)) {
            addStaticMainMethod();
        }
        this.visit((MethodDeclaration) constructorDeclaration);
        return null;
    }

    @Override
    public String visit(MethodDeclaration methodDeclaration) {
        StringBuilder argumentString = new StringBuilder();
        for (VarDeclaration arg: methodDeclaration.getArgs()) {
            argumentString.append(makeTypeSignature(arg.getType()));
        }

        if (methodDeclaration instanceof ConstructorDeclaration) {
            addCommand(String.format(".method public <init>(%s)%s", argumentString, makeReturnTypeSignature(methodDeclaration)));
        } else {
            addCommand(String.format(".method public %s(%s)%s", methodDeclaration.getMethodName().getName(), argumentString, makeReturnTypeSignature(methodDeclaration)));
        }

        addStackLocalSize();
        addCommand("aload 0");

        if (methodDeclaration instanceof ConstructorDeclaration) {
            callParentConstructor();
            initializeFields();
        }

        methodBodyVisitor(methodDeclaration);
        addCommand("");
        addCommand("");
        return null;
    }

    @Override
    public String visit(FieldDeclaration fieldDeclaration) {
        addCommand(
                String.format(
                        ".field %s %s",
                        fieldDeclaration.getVarDeclaration().getVarName().getName(),
                        makeTypeSignature(fieldDeclaration.getVarDeclaration().getType())
                )
        );
        return null;
    }

    @Override
    public String visit(VarDeclaration varDeclaration) {
        addCommand(";--- VarDeclaration call");
        putInitValue(varDeclaration.getType());
        addCommand(String.format("%s %d", "astore", slotOf(varDeclaration.getVarName().getName())));
        return null;
    }

    @Override
    public String visit(AssignmentStmt assignmentStmt) {
        //todo
        return null;
    }

    @Override
    public String visit(BlockStmt blockStmt) {
        for (Statement stmt : blockStmt.getStatements()) {
            stmt.accept(this);
        }
        return null;
    }

    @Override
    public String visit(ConditionalStmt conditionalStmt) {
        String scopeLabel = getNewLabel();
        String thenStmt = String.format("thenStmt_%s", scopeLabel);
        String elseStmt = String.format("elseStmt_%s", scopeLabel);
        String afterStmt = String.format("afterStmt_%s", scopeLabel);

        Expression condition = conditionalStmt.getCondition();
        addCommand(condition.accept(this));

        addCommand(String.format("ifeq %s", elseStmt));
        addCommand(String.format("%s:", thenStmt));

        Statement thenBody = conditionalStmt.getThenBody();
        if (thenBody != null) {
            thenBody.accept(this);
        }

        addCommand(String.format("goto %s", afterStmt));
        addCommand(String.format("%s:", elseStmt));

        Statement elseBody = conditionalStmt.getElseBody();
        if (elseBody != null) {
            elseBody.accept(this);
        }

        addCommand(String.format("%s:", afterStmt));

        return null;
    }

    @Override
    public String visit(MethodCallStmt methodCallStmt) {
        this.expressionTypeChecker.setIsInMethodCallStmt(true);
        addCommand(methodCallStmt.getMethodCall().accept(this));
        addCommand("pop");
        this.expressionTypeChecker.setIsInMethodCallStmt(false);
        return null;
    }

    @Override
    public String visit(PrintStmt print) {
        Expression arg = print.getArg();
        addCommand("getstatic java/lang/System/out Ljava/io/PrintStream;");

        Type argType = arg.accept(expressionTypeChecker);
        addCommand(arg.accept(this));
        addCommand(
                String.format("invokevirtual java/io/PrintStream/println(%s)V",
                        getPrimitiveType(argType)
                )
        );
        return null;
    }

    @Override
    public String visit(ReturnStmt returnStmt) {
        Type type = returnStmt.getReturnedExpr().accept(expressionTypeChecker);
        addCommand(returnStmt.getReturnedExpr().accept(this));
        if (type instanceof NullType) {
            addCommand("return");
        } else {
            if (type instanceof IntType || type instanceof BoolType) {
                addCommand(ConvertPrimitiveToJavaObj(type));
            }
            addCommand("areturn");
        }
        return null;
    }

    @Override
    public String visit(BreakStmt breakStmt) {
        addCommand(String.format("goto %s", breakLabelStack.pop()));
        return null;
    }

    @Override
    public String visit(ContinueStmt continueStmt) {
        addCommand(String.format("goto %s", continueLabelStack.pop()));
        return null;
    }

    @Override
    public String visit(ForeachStmt foreachStmt) {
        //todo
        return null;
    }

    @Override
    public String visit(ForStmt forStmt) {
        String scopeLabel = getNewLabel();

        String ForStmt = String.format("forStmt_%s", scopeLabel);
        String endFor = String.format("endFor_%s", scopeLabel);

        Statement initialize = forStmt.getInitialize();
        if (initialize != null) {
            initialize.accept(this);
        }

        continueLabelStack.push(ForStmt);
        breakLabelStack.push(endFor);

        addCommand(String.format("%s:", ForStmt));

        Expression condition = forStmt.getCondition();
        if (condition != null) {
            condition.accept(this);
        }

        addCommand(String.format("ifeq %s", endFor));

        Statement body = forStmt.getBody();
        if (body != null) {
            body.accept(this);
        }

        Statement update = forStmt.getUpdate();
        if (update != null) {
            update.accept(this);
        }

        addCommand(String.format("goto %s", ForStmt));
        addCommand(String.format("%s:", endFor));

        continueLabelStack.pop();
        breakLabelStack.pop();

        return null;
    }

    @Override
    public String visit(BinaryExpression binaryExpression) {
        BinaryOperator operator = binaryExpression.getBinaryOperator();
        String commands = "";

        if (!(operator == BinaryOperator.or || operator == BinaryOperator.and || operator == BinaryOperator.assign)) {
            commands += String.format("%s\n", binaryExpression.getFirstOperand().accept(this));
            commands += String.format("%s\n", binaryExpression.getSecondOperand().accept(this));
        }
        if (operator == BinaryOperator.add) {
            commands += "iadd\n";
        }
        else if (operator == BinaryOperator.sub) {
            commands += "isub\n";
        }
        else if (operator == BinaryOperator.mult) {
            commands += "imul\n";
        }
        else if (operator == BinaryOperator.div) {
            commands += "idiv\n";
        }
        else if (operator == BinaryOperator.mod) {
            commands += "irem\n";
        }
        else if((operator == BinaryOperator.gt) || (operator == BinaryOperator.lt)) {
            commands += compareExpressions(operator.toString(), "icmp");
        }
        else if((operator == BinaryOperator.eq) || (operator == BinaryOperator.neq)) {
            commands += equalityExpressions(binaryExpression.getFirstOperand(), operator.toString());
        }
        else if(operator == BinaryOperator.and) {
            commands += shortCircuit(binaryExpression);
        }
        else if(operator == BinaryOperator.or) {
            commands += shortCircuit(binaryExpression);
        }
        else if(operator == BinaryOperator.assign) {
            Type firstType = binaryExpression.getFirstOperand().accept(expressionTypeChecker);
            String secondOperandCommands = binaryExpression.getSecondOperand().accept(this);
            if(firstType instanceof ListType) {
                //todo make new list with List copy constructor with the second operand commands
                // (add these commands to secondOperandCommands)
            }
            if(binaryExpression.getFirstOperand() instanceof Identifier) {
                //todo
            }
            else if(binaryExpression.getFirstOperand() instanceof ListAccessByIndex) {
                //todo
            }
            else if(binaryExpression.getFirstOperand() instanceof ObjectOrListMemberAccess) {
                Expression instance = ((ObjectOrListMemberAccess) binaryExpression.getFirstOperand()).getInstance();
                Type memberType = binaryExpression.getFirstOperand().accept(expressionTypeChecker);
                String memberName = ((ObjectOrListMemberAccess) binaryExpression.getFirstOperand()).getMemberName().getName();
                Type instanceType = instance.accept(expressionTypeChecker);
                if(instanceType instanceof ListType) {
                    //todo
                }
                else if(instanceType instanceof ClassType) {
                    //todo
                }
            }
        }
        return commands;
    }

    @Override
    public String visit(UnaryExpression unaryExpression) {
        UnaryOperator operator = unaryExpression.getOperator();
        String commands = "";
        if(operator == UnaryOperator.minus) {
            //todo
        }
        else if(operator == UnaryOperator.not) {
            //todo
        }
        else if((operator == UnaryOperator.predec) || (operator == UnaryOperator.preinc)) {
            if(unaryExpression.getOperand() instanceof Identifier) {
                //todo
            }
            else if(unaryExpression.getOperand() instanceof ListAccessByIndex) {
                //todo
            }
            else if(unaryExpression.getOperand() instanceof ObjectOrListMemberAccess) {
                Expression instance = ((ObjectOrListMemberAccess) unaryExpression.getOperand()).getInstance();
                Type memberType = unaryExpression.getOperand().accept(expressionTypeChecker);
                String memberName = ((ObjectOrListMemberAccess) unaryExpression.getOperand()).getMemberName().getName();
                Type instanceType = instance.accept(expressionTypeChecker);
                if(instanceType instanceof ListType) {
                    //todo
                }
                else if(instanceType instanceof ClassType) {
                    //todo
                }
            }
        }
        else if((operator == UnaryOperator.postdec) || (operator == UnaryOperator.postinc)) {
            if(unaryExpression.getOperand() instanceof Identifier) {
                //todo
            }
            else if(unaryExpression.getOperand() instanceof ListAccessByIndex) {
                //todo
            }
            else if(unaryExpression.getOperand() instanceof ObjectOrListMemberAccess) {
                Expression instance = ((ObjectOrListMemberAccess) unaryExpression.getOperand()).getInstance();
                Type memberType = unaryExpression.getOperand().accept(expressionTypeChecker);
                String memberName = ((ObjectOrListMemberAccess) unaryExpression.getOperand()).getMemberName().getName();
                Type instanceType = instance.accept(expressionTypeChecker);
                if(instanceType instanceof ListType) {
                    //todo
                }
                else if(instanceType instanceof ClassType) {
                    //todo
                }
            }
        }
        return commands;
    }

    @Override
    public String visit(ObjectOrListMemberAccess objectOrListMemberAccess) {
        Type memberType = objectOrListMemberAccess.accept(expressionTypeChecker);
        Type instanceType = objectOrListMemberAccess.getInstance().accept(expressionTypeChecker);
        String memberName = objectOrListMemberAccess.getMemberName().getName();
        String commands = "";
        if(instanceType instanceof ClassType) {
            String className = ((ClassType) instanceType).getClassName().getName();
            try {
                SymbolTable classSymbolTable = ((ClassSymbolTableItem) SymbolTable.root.getItem(ClassSymbolTableItem.START_KEY + className, true)).getClassSymbolTable();
                try {
                    classSymbolTable.getItem(FieldSymbolTableItem.START_KEY + memberName, true);
                    commands += objectOrListMemberAccess.getInstance().accept(this);
                    commands += String.format
                                (
                                    "getfield %s/%s %s",
                                    ((ClassType) instanceType).getClassName().getName(),
                                    memberName,
                                    makeTypeSignature(memberType)
                                );
                    commands += "\n";
                    commands += convertJavaObjToPrimitive(memberType);
                    commands += "\n";
                } catch (ItemNotFoundException memberIsMethod) {
                    int tempSlot = slotOf("");
                    commands += "new Fptr\n";
                    commands += "dup\n";
                    commands += objectOrListMemberAccess.getInstance().accept(this);
                    commands += String.format("ldc \"%s\"\n", memberName);
                    commands += "invokespecial Fptr/<init>(Ljava/lang/Object;Ljava/lang/String;)V\n";
                    commands += String.format("astore %d\n", tempSlot);

                }
            } catch (ItemNotFoundException classNotFound) {
            }
        }
        else if(instanceType instanceof ListType) {
            commands += objectOrListMemberAccess.getInstance().accept(this);
            ArrayList<ListNameType> listElements = ((ListType) instanceType).getElementsTypes();
            int index = 0;
            for (ListNameType listElement : listElements) {
                if (listElement.getName().getName().equals(memberName)) {
                    commands += String.format("ldc %d\n", index);
                    commands += "invokevirtual List/getElement(I)Ljava/lang/Object;\n";
                    commands += castObject(listElement.getType());
                    commands += "\n";
                    commands += convertJavaObjToPrimitive(listElement.getType());
                    commands += "\n";
                    break;
                }
                index += 1;
            }

        }
        return commands;
    }

    @Override
    public String visit(Identifier identifier) {
        String commands = "";

        int slotNumber = slotOf(identifier.getName());
        Type type = identifier.accept(expressionTypeChecker);
        String primitiveTypeConverter = convertJavaObjToPrimitive(type);
        commands += String.format("aload_%d\n", slotNumber);
        commands += !primitiveTypeConverter.equals("") ?
                    String.format("%s\n", primitiveTypeConverter) :
                    "";

        return commands;
    }

    @Override
    public String visit(ListAccessByIndex listAccessByIndex) {
        String commands = "";
        //todo
        return commands;
    }

    @Override
    public String visit(MethodCall methodCall) {
        StringBuilder commands = new StringBuilder();
        commands.append(getNewArrayList());
        int tempSlot = slotOf("");
        commands.append(String.format("astore %d\n", tempSlot));
        Expression instance = methodCall.getInstance();
        FptrType instanceType = (FptrType) instance.accept(expressionTypeChecker);
        commands.append(String.format("%s\n", instance.accept(this)));
        // instance, method on stack

        for (Expression arg: methodCall.getArgs()) {
            Type argType = arg.accept(expressionTypeChecker);
            commands.append(String.format("aload %d\n", tempSlot));
            commands.append(arg.accept(this));

            if (argType instanceof IntType || argType instanceof BoolType) {
                commands.append(ConvertPrimitiveToJavaObj(argType));
                commands.append("\n");
            }

            commands.append("\n");
            commands.append("invokevirtual java/util/ArrayList/add(Ljava/lang/Object;)Z\n");
            commands.append("pop\n");
        }

        commands.append(String.format("aload %d\n", slotOf("-1")));
        commands.append(String.format("aload %d\n", tempSlot));
        commands.append("invokevirtual Fptr/invoke(Ljava/util/ArrayList;)Ljava/lang/Object;\n");

        Type returnType = instanceType.getReturnType();
        commands.append(castObject(returnType));
        commands.append(convertJavaObjToPrimitive(returnType));

        return commands.toString();
    }

    @Override
    public String visit(NewClassInstance newClassInstance) {
        String commands = "";
        //todo
        return commands;
    }

    @Override
    public String visit(ThisClass thisClass) {
        return "aload 0\n";
    }

    @Override
    public String visit(ListValue listValue) {
        StringBuilder commands = new StringBuilder();
        commands.append(getNewArrayList());
        int tempSlot = slotOf("");
        commands.append(String.format("astore %d\n", tempSlot));
        ArrayList<Expression> listElements = listValue.getElements();
        for (Expression listElement : listElements) {
            commands.append(String.format("aload %d\n", tempSlot));
            commands.append(listElement.accept(this));
            Type elementType = listElement.accept(expressionTypeChecker);
            String primitiveTypeConverter = convertJavaObjToPrimitive(elementType);
            commands.append(
                        !primitiveTypeConverter.equals("") ?
                        String.format("%s\n", primitiveTypeConverter) :
                        ""
                    );
            commands.append("invokevirtual java/util/ArrayList/add(Ljava/lang/Object;)Z\n");
            commands.append("pop\n");
        }

        commands.append(getNewList());
        commands.append(String.format("aload %d\n", tempSlot));
        commands.append("invokespecial List/<init>(Ljava/util/ArrayList;)V\n");
        return commands.toString();
    }

    @Override
    public String visit(NullValue nullValue) {
        return "aconst_null\n";
    }

    @Override
    public String visit(IntValue intValue) {
        return String.format("ldc %d\n", intValue.getConstant());
    }

    @Override
    public String visit(BoolValue boolValue) {
        int value = 0;
        if (boolValue.getConstant()) {
            value = 1;
        }
        return String.format("ldc %d\n", value);
    }

    @Override
    public String visit(StringValue stringValue) {
        return String.format("ldc \"%s\"\n", stringValue.getConstant());
    }

}