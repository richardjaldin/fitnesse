package fitnesse.responders.run.slimResponder;

import static java.lang.Character.isLetterOrDigit;
import static java.lang.Character.toUpperCase;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class SlimTable {
  protected Table table;
  private SlimTestContext testContext;
  protected String id;
  private String tableName;
  private int instructionNumber = 0;
  private List<Object> instructions;
  private boolean isLiteralTable;
  private List<Expectation> expectations = new ArrayList<Expectation>();

  public SlimTable(Table table, String id) {
    this(table, id, new LocalSlimTestContext());
  }

  public SlimTable(Table table, String id, SlimTestContext testContext) {
    this.id = id;
    this.table = table;
    this.testContext = testContext;
    tableName = getTableType() + "_" + id;
    instructions = new ArrayList<Object>();
    isLiteralTable = table.isLiteralTable();
  }

  protected void addExpectation(Expectation e) {
    expectations.add(e);
  }

  public String replaceSymbols(String s) {
    return new SymbolReplacer(s).replace();
  }

  public String replaceSymbolsWithFullExpansion(String s) {
    return new FullExpansionSymbolReplacer(s).replace();
  }


  protected abstract String getTableType();

  public void appendInstructions(List<Object> instructions) {
    try {
      this.instructions = instructions;
      appendInstructions();
    } catch (Throwable e) {
      String tableName = table.getCellContents(0, 0);
      table.setCell(0, 0, fail(String.format("!-%s: Bad table: %s-!", tableName, e.getMessage())));
    }
  }

  public abstract void appendInstructions();

  protected List<Object> prepareInstruction() {
    List<Object> instruction = new ArrayList<Object>();
    instruction.add(makeInstructionTag(instructionNumber));
    instructionNumber++;
    return instruction;
  }

  protected String makeInstructionTag(int instructionNumber) {
    return String.format("%s_%d", tableName, instructionNumber);
  }

  protected int getInstructionNumber() {
    return instructionNumber;
  }

  protected String getTableName() {
    return tableName;
  }

  protected void addInstruction(List<Object> instruction) {
    instructions.add(instruction);
  }

  protected void literalizeTable() {
    if (isLiteralTable) {
      table.setAsNotLiteralTable();
      for (int row = 0; row < table.getRowCount(); row++) {
        for (int col = 0; col < table.getColumnCountInRow(row); col++) {
          table.setCell(col, row, literalize(table.getCellContents(col, row)));
        }
      }
    }
  }

  protected String literalize(String contents) {
    return isLiteralTable ? String.format("!-%s-!", contents) : contents;
  }

  public void evaluateExpectations(Map<String, Object> returnValues) throws Exception {
    literalizeTable();
    for (Expectation expectation : expectations)
      expectation.evaluateExpectation(returnValues, this);
    evaluateReturnValues(returnValues);
  }

  protected abstract void evaluateReturnValues(Map<String, Object> returnValues) throws Exception;

  public String getSymbol(String variableName) {
    return testContext.getSymbol(variableName);
  }

  public void setSymbol(String variableName, String value) {
    testContext.setSymbol(variableName, value);
  }

  public Table getTable() {
    return table;
  }

  protected void constructFixture() {
    String tableHeader = table.getCellContents(0, 0);
    String fixtureName = tableHeader.split(":")[1];
    String disgracedFixtureName = Disgracer.disgraceClassName(fixtureName);
    constructInstance(getTableName(), disgracedFixtureName, 0, 0);
  }

  protected void constructInstance(String instanceName, String className, int classNameColumn, int row) {
    Expectation expectation = new ConstructionExpectation(getInstructionNumber(), classNameColumn, row);
    addExpectation(expectation);
    List<Object> makeInstruction = prepareInstruction();
    makeInstruction.add("make");
    makeInstruction.add(instanceName);

    makeInstruction.add(className);
    for (String argument : cellsStartingAt(classNameColumn + 1, row))
      makeInstruction.add(argument);
    addInstruction(makeInstruction);
  }

  protected String[] cellsStartingAt(int startingColumn, int row) {
    int columnCount = table.getColumnCountInRow(row);
    List<String> arguments = new ArrayList<String>();
    for (int col = startingColumn; col < columnCount; col++)
      arguments.add(table.getCellContents(col, row));
    return arguments.toArray(new String[0]);
  }

  protected void addCall(List<Object> instruction, String instanceName, String functionName) {
    instruction.add("call");
    instruction.add(instanceName);
    instruction.add(Disgracer.disgraceMethodName(functionName));
  }

  protected String callFunction(String instanceName, String functionName, String... args) {
    List<Object> callInstruction = prepareInstruction();
    addCall(callInstruction, instanceName, functionName);
    for (String arg : args)
      callInstruction.add(arg);
    addInstruction(callInstruction);
    return (String) callInstruction.get(0);
  }

  protected void failMessage(int col, int row, String failureMessage) {
    String contents = table.getCellContents(col, row);
    String failingContents = failMessage(contents, failureMessage);
    table.setCell(col, row, failingContents);
  }

  protected void fail(int col, int row, String value) {
    String failingContents = fail(value);
    table.setCell(col, row, failingContents);
  }

  protected void pass(int col, int row) {
    String contents = table.getCellContents(col, row);
    String passingContents = pass(contents);
    table.setCell(col, row, passingContents);
  }

  protected void expected(int col, int tableRow, String actual) {
    String contents = table.getCellContents(col, tableRow);
    String failureMessage = failMessage(actual, String.format("expected [%s]", contents));
    table.setCell(col, tableRow, failureMessage);
  }

  protected String fail(String value) {
    return String.format("!style_fail(%s)", value);
  }

  protected String failMessage(String value, String message) {
    return String.format("[%s] !style_fail(%s)", value, message);
  }

  protected String pass(String value) {
    return String.format("!style_pass(%s)", value);
  }

  protected ReturnedValueExpectation getReturnedValueExpectation(
    String expected, int instructionNumber, int col, int row
  ) {
    return new ReturnedValueExpectation(expected, instructionNumber, col, row);
  }

  static class Disgracer {
    public boolean capitalizeNextWord;
    public StringBuffer disgracedName;
    private String name;

    public Disgracer(String name) {
      this.name = name;
    }

    public static String disgraceClassName(String name) {
      return new Disgracer(name).disgraceClassNameIfNecessary();
    }

    public static String disgraceMethodName(String name) {
      return new Disgracer(name).disgraceMethodNameIfNecessary();
    }

    private String disgraceMethodNameIfNecessary() {
      if (isGraceful()) {
        return disgraceMethodName();
      } else {
        return name;
      }
    }

    private String disgraceMethodName() {
      capitalizeNextWord = false;
      return disgraceName();
    }

    private String disgraceClassNameIfNecessary() {
      if (nameHasDots())
        return name;
      else if (isGraceful()) {
        return disgraceClassName();
      } else {
        return name;
      }
    }

    private String disgraceClassName() {
      capitalizeNextWord = true;
      return disgraceName();
    }

    private boolean nameHasDots() {
      return name.indexOf(".") != -1;
    }

    private String disgraceName() {
      disgracedName = new StringBuffer();
      for (char c : name.toCharArray())
        appendCharInProperCase(c);

      return disgracedName.toString();
    }

    private void appendCharInProperCase(char c) {
      if (isGraceful(c)) {
        capitalizeNextWord = true;
      } else {
        appendProperlyCapitalized(c);
      }
    }

    private void appendProperlyCapitalized(char c) {
      disgracedName.append(capitalizeNextWord ? toUpperCase(c) : c);
      capitalizeNextWord = false;
    }

    private boolean isGraceful() {
      boolean isGraceful = false;
      for (char c : name.toCharArray()) {
        if (isGraceful(c))
          isGraceful = true;
      }
      return isGraceful;
    }

    private boolean isGraceful(char c) {
      return !(isLetterOrDigit(c) || c == '_');
    }
  }

  public abstract static class Expectation {
    protected String expectedValue;
    protected int col;
    protected int row;
    protected int instructionNumber;
    protected SlimTable slimTable;

    public Expectation(String expected, int instructionNumber, int col, int row) {
      expectedValue = expected;
      this.row = row;
      this.instructionNumber = instructionNumber;
      this.col = col;
    }

    protected void evaluateExpectation(Map<String, Object> returnValues, SlimTable slimTable) {
      this.slimTable = slimTable;
      String value = (String) returnValues.get(slimTable.makeInstructionTag(instructionNumber));
      String literalizedValue = slimTable.literalize(value);
      String originalContent = slimTable.table.getCellContents(col, row);
      String evaluationMessage = createEvaluationMessage(value, literalizedValue, originalContent);
      slimTable.table.setCell(col, row, evaluationMessage);
    }

    protected abstract String createEvaluationMessage(String value, String literalizedValue, String originalValue);

    protected void setSlimTable(SlimTable slimTable) {
      this.slimTable = slimTable;
    }
  }

  private static class LocalSlimTestContext implements SlimTestContext {
    private Map<String, String> symbols = new HashMap<String, String>();

    public String getSymbol(String symbolName) {
      return symbols.get(symbolName);
    }

    public void setSymbol(String symbolName, String value) {
      symbols.put(symbolName, value);
    }
  }

  class SymbolReplacer {
    protected String stringToReplace;

    SymbolReplacer(String s) {
      this.stringToReplace = s;
    }

    String replace() {
      Pattern symbolPattern = Pattern.compile("\\$([a-zA-Z]\\w*)");
      int startingPosition = 0;
      while (true) {
        Matcher symbolMatcher = symbolPattern.matcher(stringToReplace.substring(startingPosition));
        if (symbolMatcher.find()) {
          startingPosition += replaceSymbol(symbolMatcher);
        } else
          break;
      }
      return stringToReplace;
    }

    private int replaceSymbol(Matcher symbolMatcher) {
      String symbolName = symbolMatcher.group(1);
      if (getSymbol(symbolName) != null)
        stringToReplace = stringToReplace.replace("$" + symbolName, translate(symbolName));
      return symbolMatcher.start(1);
    }

    protected String translate(String symbolName) {
      return getSymbol(symbolName);
    }

  }

  class FullExpansionSymbolReplacer extends SymbolReplacer {
    FullExpansionSymbolReplacer(String s) {
      super(s);
    }

    protected String translate(String symbolName) {
      return String.format("$%s->[%s]", symbolName, getSymbol(symbolName));
    }
  }

  public static class SyntaxError extends Error {
    public SyntaxError(String message) {
      super(message);
    }
  }

  class VoidReturnExpectation extends Expectation {
    public VoidReturnExpectation(int instructionNumber, int col, int row) {
      super(null, instructionNumber, col, row);
    }

    protected String createEvaluationMessage(String value, String literalizedValue, String originalValue) {
      if (value.indexOf("Exception") != -1)
        return fail(literalizedValue);
      else {
        return slimTable.replaceSymbolsWithFullExpansion(originalValue);
      }
    }
  }

  class ConstructionExpectation extends Expectation {
    public ConstructionExpectation(int instructionNumber, int col, int row) {
      super(null, instructionNumber, col, row);
    }

    protected String createEvaluationMessage(String value, String literalizedValue, String originalValue) {
      if (value.indexOf("Exception") != -1)
        return fail(literalizedValue);
      else {
        return pass(originalValue);
      }
    }
  }

  class ReturnedValueExpectation extends Expectation {
    public ReturnedValueExpectation(String expected, int instructionNumber, int col, int row) {
      super(expected, instructionNumber, col, row);
    }

    protected String createEvaluationMessage(String value, String literalizedValue, String originalValue) {
      String evaluationMessage;
      String replacedValue = slimTable.replaceSymbols(expectedValue);
      if (value.equals(replacedValue))
        evaluationMessage = pass(announceBlank(originalValue));
      else if (replacedValue.length() == 0)
        evaluationMessage = String.format("!style_ignore(%s)", literalizedValue);
      else {
        String expressionMessage = new Comparator(replacedValue, value, expectedValue).evaluate();
        if (expressionMessage != null)
          evaluationMessage = expressionMessage;
        else
          evaluationMessage = failMessage(literalizedValue, String.format("expected [%s]", originalValue));
      }

      return slimTable.replaceSymbolsWithFullExpansion(evaluationMessage);
    }

    private String announceBlank(String originalValue) {
      return originalValue.length() == 0 ? "BLANK" : originalValue;
    }

    class Comparator {
      private String expression;
      private String value;
      private String originalExpression;
      private Pattern simpleComparison = Pattern.compile(
        "\\A\\s*_?\\s*((?:[<>]=?)|(?:!=))\\s*(\\d*\\.?\\d+)\\s*\\Z"
      );
      private Pattern range = Pattern.compile(
        "\\A\\s*(\\d*\\.?\\d+)\\s*<(=?)\\s*_\\s*<(=?)\\s*(\\d*\\.?\\d+)\\s*\\Z"
      );
      private double v;
      private double arg1;
      private double arg2;
      public String operation;

      private Comparator(String expression, String value, String originalExpression) {
        this.expression = expression;
        this.value = value;
        this.originalExpression = originalExpression;
      }

      private String evaluate() {
        operation = matchSimpleComparison();
        if (operation != null)
          return doSimpleComparison();

        Matcher matcher = range.matcher(expression);
        if (matcher.matches() && canUnpackRange(matcher)) {
          return doRange(matcher);
        } else
          return null;
      }

      private String doRange(Matcher matcher) {
        boolean closedLeft = matcher.group(2).equals("=");
        boolean closedRight = matcher.group(3).equals("=");
        boolean pass = (arg1 < v && v < arg2) || (closedLeft && arg1 == v) || (closedRight && arg2 == v);
        return rangeMessage(pass);
      }

      private String rangeMessage(boolean pass) {
        String[] fragments = originalExpression.replaceAll(" ", "").split("_");
        String message = String.format("%s%s%s", fragments[0], value, fragments[1]);
        return pass ? pass(message) : fail(message);

      }

      private boolean canUnpackRange(Matcher matcher) {
        try {
          arg1 = Double.parseDouble(matcher.group(1));
          arg2 = Double.parseDouble(matcher.group(4));
          v = Double.parseDouble(value);
        } catch (NumberFormatException e) {
          return false;
        }
        return true;
      }

      private String doSimpleComparison() {
        if (operation.equals("<"))
          return simpleComparisonMessage(v < arg1);
        else if (operation.equals(">"))
          return simpleComparisonMessage(v > arg1);
        else if (operation.equals(">="))
          return simpleComparisonMessage(v >= arg1);
        else if (operation.equals("<="))
          return simpleComparisonMessage(v <= arg1);
        else if (operation.equals("!="))
          return simpleComparisonMessage(v != arg1);
        else
          return null;
      }

      private String simpleComparisonMessage(boolean pass) {
        String message = String.format("%s%s", value, originalExpression.replaceAll(" ", ""));
        return pass ? pass(message) : fail(message);

      }

      private String matchSimpleComparison() {
        Matcher matcher = simpleComparison.matcher(expression);
        if (matcher.matches()) {
          try {
            v = Double.parseDouble(value);
            arg1 = Double.parseDouble(matcher.group(2));
            return matcher.group(1);
          } catch (NumberFormatException e1) {
            return null;
          }
        }
        return null;
      }
    }
  }
}