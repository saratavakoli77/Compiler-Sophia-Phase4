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
import main.visitor.Visitor;
import main.visitor.typeChecker.ExpressionTypeChecker;
//import utilities.codeGenerationUtilityClasses.*;

import java.io.*;

public class CodeGenerator extends Visitor<String> {
    ExpressionTypeChecker expressionTypeChecker;
    Graph<String> classHierarchy;
    private String outputPath;
    private FileWriter currentFile;
    private ClassDeclaration currentClass;
    private MethodDeclaration currentMethod;

    public CodeGenerator(Graph<String> classHierarchy) {
        this.classHierarchy = classHierarchy;
        this.expressionTypeChecker = new ExpressionTypeChecker(classHierarchy);
        this.prepareOutputFolder();
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

//    private String makeTypeSignature(Type t) {
//        if (t instanceof IntType)
//            return "I";
//        else if (t instanceof ListType) //todo: is it ok?
//            return "[I";
//        else if (t instanceof BoolType)
//            return "Z";
//        else if (t instanceof StringType)
//            return "Ljava/lang/String;";
//        else if (t instanceof ClassType)
//            return String.format("L%s;", ((ClassType) t).getClassName().getName());
//        else
//            return "ERROR Type";
//        //todo fptrType
//    }

    private String makeTypeSignature(Type t) {
        if (t instanceof IntType)
            return "Ljava/lang/Integer;";
        else if (t instanceof ListType) //todo: is it ok?
            return "[I";
        else if (t instanceof BoolType)
            return "Ljava/lang/Boolean;";
        else if (t instanceof StringType)
            return "Ljava/lang/String;";
        else if (t instanceof ClassType)
            return String.format("L%s;", ((ClassType) t).getClassName().getName());
        else
            return "ERROR Type";
        //todo fptrType
    }

    private String makeReturnTypeSignature(MethodDeclaration methodDeclaration) {
        if (methodDeclaration.getReturnType() instanceof NullType) {
            return "V";
        } else {
            return makeTypeSignature(methodDeclaration.getReturnType());
        }
    }

    private String putInitValue(VarDeclaration varDeclaration) {
        Type varType = varDeclaration.getType();
        //addCommand("aload_0"); //todo: should it be here? or just in addDefaultConstructor
        if (varType instanceof IntType || varType instanceof BoolType) {
            addCommand("iconst_0");
            return "istore";
        } else if (varType instanceof StringType) {
            addCommand("ldc \"\"");
            return "astore";
        } else if (varType instanceof FptrType || varType instanceof ClassType) {
            addCommand("aconst_null");
            return "astore";
        } else if (varType instanceof ListType) {
            //todo: how to add new List?

            return "astore";
        }
        return "";
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
            putInitValue(varDec);
            addCommand(
                    String.format(
                            "putfield %s/%s %s",
                            currentClass.getClassName().getName(),
                            varDec.getVarName().getName(),
                            varDec.getType()
                    )
            );
        }
    }

    private void addDefaultConstructor() {
        addCommand(".method public <init>()V"); //todo: input is empty or I?
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

        if (identifier.equals("")) {
            return index;
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
            putInitValue(localVar);
        }

        for (Statement statement: methodDeclaration.getBody()) {
            statement.accept(this);

        }

        if (!methodDeclaration.getDoesReturn()) {
            addCommand("return");
        }

        addCommand(".end method");
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
            currentMethod = classDeclaration.getConstructor();
            classDeclaration.getConstructor().accept(this);
        } else {
            addDefaultConstructor();
        }

        for (MethodDeclaration methodDeclaration : classDeclaration.getMethods()) {
            this.expressionTypeChecker.setCurrentMethod(methodDeclaration);
            this.currentMethod = methodDeclaration;
            methodDeclaration.accept(this);
        }

        return null;
    }

    @Override
    public String visit(ConstructorDeclaration constructorDeclaration) {
        //todo add default constructor or static main method if needed
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
        //addCommand(";in visit MethodDeclaration");
        addCommand("aload 0");

        if (methodDeclaration instanceof ConstructorDeclaration) {
            callParentConstructor();
            //addCommand("aload_0");
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
        String storeCommand = putInitValue(varDeclaration);
        addCommand(String.format("%s %d", storeCommand, slotOf(varDeclaration.getVarName().getName())));
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
        //todo
        return null;
    }

    @Override
    public String visit(MethodCallStmt methodCallStmt) {
        //todo
        return null;
    }

    @Override
    public String visit(PrintStmt print) {
        Expression arg = print.getArg();
        addCommand("getstatic java/lang/System/out Ljava/io/PrintStream");

        Type argType = arg.accept(expressionTypeChecker);
        addCommand(
                String.format("invokevirtual java/io/PrintStream/println(%s)V",
                        makeTypeSignature(argType)
                )
        );
        return null;
    }

    @Override
    public String visit(ReturnStmt returnStmt) {
        Type type = returnStmt.getReturnedExpr().accept(expressionTypeChecker);
        if (type instanceof NullType) {
            addCommand("return");
        } else {
            if (type instanceof IntType) {
                addCommand("invokestatic java/lang/Integer/valueOf(I)Ljava/lang/Integer;");
            } else if (type instanceof BoolType) {
                addCommand("invokestatic java/lang/Boolean/valueOf(Z)Ljava/lang/Boolean;");
            }
            addCommand("areturn");
        }
        return null;
    }

    @Override
    public String visit(BreakStmt breakStmt) {
        //todo
        return null;
    }

    @Override
    public String visit(ContinueStmt continueStmt) {
        //todo
        return null;
    }

    @Override
    public String visit(ForeachStmt foreachStmt) {
        //todo
        return null;
    }

    @Override
    public String visit(ForStmt forStmt) {
        //todo
        return null;
    }

    @Override
    public String visit(BinaryExpression binaryExpression) {
        BinaryOperator operator = binaryExpression.getBinaryOperator();
        String commands = "";
        if (operator == BinaryOperator.add) {
            //todo
        }
        else if (operator == BinaryOperator.sub) {
            //todo
        }
        else if (operator == BinaryOperator.mult) {
            //todo
        }
        else if (operator == BinaryOperator.div) {
            //todo
        }
        else if (operator == BinaryOperator.mod) {
            //todo
        }
        else if((operator == BinaryOperator.gt) || (operator == BinaryOperator.lt)) {
            //todo
        }
        else if((operator == BinaryOperator.eq) || (operator == BinaryOperator.neq)) {
            //todo
        }
        else if(operator == BinaryOperator.and) {
            //todo
        }
        else if(operator == BinaryOperator.or) {
            //todo
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
                    //todo it is a field
                } catch (ItemNotFoundException memberIsMethod) {
                    //todo it is a method (new instance of Fptr)
                }
            } catch (ItemNotFoundException classNotFound) {
            }
        }
        else if(instanceType instanceof ListType) {
            //todo
        }
        return commands;
    }

    @Override
    public String visit(Identifier identifier) {
        String commands = "";
        //todo
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
        String commands = "";
        //todo
        return commands;
    }

    @Override
    public String visit(NewClassInstance newClassInstance) {
        String commands = "";
        //todo
        return commands;
    }

    @Override
    public String visit(ThisClass thisClass) {
        String commands = "";
        //todo
        return commands;
    }

    @Override
    public String visit(ListValue listValue) {
        String commands = "";
        //todo
        return commands;
    }

    @Override
    public String visit(NullValue nullValue) {
        String commands = "";
        //todo
        return commands;
    }

    @Override
    public String visit(IntValue intValue) {
        String commands = "";
        //todo
        return commands;
    }

    @Override
    public String visit(BoolValue boolValue) {
        String commands = "";
        //todo
        return commands;
    }

    @Override
    public String visit(StringValue stringValue) {
        String commands = "";
        //todo
        return commands;
    }

}