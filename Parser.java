import java.io.Writer;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.IOException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Dictionary;

class Util {
  public static String escapeChar(char c) {
    switch (c) {
      case '\t': return "'\\t'";
      case '\n': return "'\\n'";
      case '\r': return "'\\r'";
      case '\b': return "'\\b'";
      case '\f': return "'\\f'";
      case '\'': return "'\\''";
      default: break;
    }

    if ((c >= '!' && c <= '~')
        || Character.isLetter(c) || Character.isDigit(c))
      return "'" + c + "'";

    return String.format("'\\u%04x'", (int) c);
  }

  public static void writeSpan(Writer wr, String src,
                               int spanBegin, int spanLength)
      throws IOException {
    // find (and write) the line surrounding the problematic span
    int lineBegin = spanBegin;
    if (lineBegin >= src.length())
      lineBegin = src.length() - 1;
    while (lineBegin > 0 && src.charAt(lineBegin) != '\n')
      --lineBegin;
    int lineLength = spanLength;
    while (lineBegin + lineLength < src.length()
           && src.charAt(lineBegin + lineLength) != '\n') {
      ++lineLength;
    }
    wr.write(src, lineBegin, lineLength);
    wr.append('\n');

    // write whitespace until we're "under" the problematic span
    for (int i = lineBegin; i < spanBegin; ++i) {
      if (src.charAt(i) == '\t')
        wr.append('\t');
      else
        wr.append(' ');
    }
    // underline the problematic span, like this:
    //           ^~~~~~~~~~~~~~~~~~~^
    wr.append('^');
    for (int i = spanBegin + 1; i < spanBegin + spanLength - 1; ++i)
      wr.append('~');
    if (spanLength > 1)
      wr.append('^');
    wr.append('\n');
  }
}

enum TokenType {
  LIT_NUM,
  IDENT,
  OP_PLUS,
  OP_MINUS,
  OP_MUL,
  OP_DIV,
  PAREN_OPEN,
  PAREN_CLOSE,
}

class Token {
  public TokenType type;
  public Object content;
  public int spanBegin;
  public int spanLength;

  public Token(TokenType type, Object content, int spanBegin, int spanLength) {
    if (spanLength < 0)
      throw new IllegalArgumentException("spanLength negative: " + spanLength);
    this.type       = type;
    this.content    = content;
    this.spanBegin  = spanBegin;
    this.spanLength = spanLength;
  }

  public void print(String src) throws IOException {
    System.out.print(
        String.format("Token %-11s ", type.name()));
    switch (type) {
      case LIT_NUM:
        System.out.println(((Double) content).toString());
        break;
      case IDENT:
        System.out.println((String) content);
        break;
      case OP_PLUS:
      case OP_MINUS:
      case OP_MUL:
      case OP_DIV:
      case PAREN_OPEN:
      case PAREN_CLOSE:
        System.out.println();
        break;
    }
    BufferedWriter wr = new BufferedWriter(
        new OutputStreamWriter(System.out));
    Util.writeSpan(wr, src, spanBegin, spanLength);
    wr.flush();
  }
}

class SpanException extends Exception {
  public SpanException(String msg, 
                            int spanBegin, int spanLength) {
    super(msg);
    this.spanBegin  = spanBegin;
    this.spanLength = spanLength;
  }

  public void writeSpan(Writer wr, String src) throws IOException {
    Util.writeSpan(wr, src, spanBegin, spanLength);
  }

  public int spanBegin;
  public int spanLength;
}

class Tokenizer implements Iterator<Token> {
  public Tokenizer(String sourceText) {
    src = sourceText;
    pos = 0;
    prepareNextToken();
  }

  public boolean hasNext() {
    return currentToken != null;
  }

  public Token next() {
    Token t = currentToken;
    if (t == null)
      throw new NoSuchElementException();
    prepareNextToken();
    return t;
  }

  public SpanException getError() {
    return exception;
  }

  public void remove() {
    throw new UnsupportedOperationException();
  }

  private void prepareNextToken() {
    try {
      currentToken = consumeToken();
    } catch (SpanException e) {
      exception = e;
      currentToken = null;
    }
  }

  private Token consumeToken() throws SpanException {
    consumeWhitespace();
    if (pos >= src.length())
      return null;
    char c = src.charAt(pos);
    if (c >= '0' && c <= '9')
      return consumeLiteralNumber();

    if (Character.isLetter(c) || 'c' == '_')
      return consumeIdentifier();

    ++pos;
    switch (c) {
      case '+': return new Token(TokenType.OP_PLUS, null, pos - 1, 1);
      case '-': return new Token(TokenType.OP_MINUS, null, pos - 1, 1);
      case '*': return new Token(TokenType.OP_MUL, null, pos - 1, 1);
      case '/': return new Token(TokenType.OP_DIV, null, pos - 1, 1);
      case '(': return new Token(TokenType.PAREN_OPEN, null, pos - 1, 1);
      case ')': return new Token(TokenType.PAREN_CLOSE, null, pos - 1, 1);
      default: break;
    }

    fatal("illegal start of token: " + Util.escapeChar(c), pos - 1, pos);
    return null;
  }

  private Token consumeLiteralNumber() throws SpanException {
    int begin = pos;
    consumeDigits();
    if (pos < src.length() && src.charAt(pos) == '.') {
      ++pos;
      if (pos >= src.length())
        fatal("illegal end of input after decimal point", begin, pos);
      char c = src.charAt(pos);
      if (!(c >= '0' && c <= '9')) {
        fatal("illegal character after decimal point: " + Util.escapeChar(c),
              begin, pos);
      }
      consumeDigits();
    }
    Token t = new Token(TokenType.LIT_NUM,
        Double.valueOf(src.substring(begin, pos)),
        begin, pos - begin);
    return t;
  }

  private Token consumeIdentifier() throws SpanException {
    int begin = pos;
    ++pos;
    consumeIdentChars();
    Token t = new Token(TokenType.IDENT,
        src.substring(begin, pos),
        begin, pos - begin); 
    return t;
  }

  private void fatal(String msg, int spanBegin, int spanEnd)
      throws SpanException {
    throw new SpanException(msg, spanBegin, spanEnd - spanBegin);
  }

  private void consumeDigits() {
    while (pos < src.length()) {
      char c = src.charAt(pos);
      if (!(c >= '0' && c <= '9'))
        break;
      ++pos;
    }
  }

  private void consumeIdentChars() {
    while (pos < src.length()) {
      char c = src.charAt(pos);
      if (!(Character.isLetter(c)
            || Character.isDigit(c))
            || c == '_')
        break;
      ++pos;
    }
  }

  private void consumeWhitespace() {
    while (pos < src.length() && Character.isWhitespace(src.charAt(pos)))
      ++pos;
  }

  private String src;
  private int pos;
  private Token currentToken;
  private SpanException exception;
}

abstract class Expr {
  public abstract double eval(Dictionary<String, Double> vars);
  public abstract String toString();
}

class LiteralExpr extends Expr {
  public double eval(Dictionary<String, Double> _vars) {
    return value;
  }

  public String toString() {
    return Double.toString(value);
  }

  public LiteralExpr(double v) {
    value = v;
  }

  double value;
}

class VarExpr extends Expr {
  public double eval(Dictionary<String, Double> vars) {
    return vars.get(ident);
  }

  public String toString() {
    return ident;
  }

  public VarExpr(String i) {
    ident = i;
  }

  String ident;
}

enum Binop {
  ADD, SUBTRACT, MULTIPLY, DIVIDE;

  public String toString() {
    switch (this) {
    case ADD:      return "+";
    case SUBTRACT: return "-";
    case MULTIPLY: return "*";
    case DIVIDE:   return "/";
    default: return null;
    }
  }

  public double apply(double lhs, double rhs) {
    switch (this) {
    case ADD:      return lhs + rhs;
    case SUBTRACT: return lhs - rhs;
    case MULTIPLY: return lhs * rhs;
    case DIVIDE:   return lhs / rhs;
    default: return -1;
    }
  }
}

class BinopExpr extends Expr {
  public double eval(Dictionary<String, Double> vars) {
    return op.apply(lhs.eval(vars), rhs.eval(vars));
  }

  public String toString() {
    return "[" + lhs.toString() + " " + op.toString() + " " + rhs.toString() + "]";
  }

  public BinopExpr(Binop o, Expr l, Expr r) {
    op = o;
    lhs = l;
    rhs = r;
  }

  Binop op;
  Expr lhs, rhs;
}

class FnExpr extends Expr {
  public double eval(Dictionary<String, Double> vars) {
    // TODO: hardcode sin/cos or whatever
    return arg.eval(vars);
  }

  public String toString() {
    return fn + "(" + arg.toString() + ")";
  }

  public FnExpr(String f, Expr a) {
    fn = f;
    arg = a;
  }

  String fn;
  Expr arg;
}

class ParenExpr extends Expr {
  public double eval(Dictionary<String, Double> vars) {
    return inner.eval(vars);
  }

  public String toString() {
    return "(" + inner.toString() + ")";
  }

  public ParenExpr(Expr i) {
    inner = i;
  }

  Expr inner;
}

class NegateExpr extends Expr {
  public double eval(Dictionary<String, Double> vars) {
    return -inner.eval(vars);
  }

  public String toString() {
    return "-" + inner.toString();
  }

  public NegateExpr(Expr i) {
    inner = i;
  }

  Expr inner;
}

public class Parser {
  static Expr parse(String src) throws SpanException {
    Parser p = new Parser(src);
    return p.parse();
  }

  public static void main(String[] args) throws IOException, SpanException {
    String src = args[0];
    try {
      Expr e = parse(src);
      System.out.println(e.toString());
    } catch (SpanException error) {
    // Tokenizer t = new Tokenizer(src);
    // while (t.hasNext()) {
    //   t.next().print(src);
    // }
    // SpanException error = t.getError();
    // if (error != null) {
      System.out.println("error: " + error.toString());
      BufferedWriter wr = new BufferedWriter(
          new OutputStreamWriter(System.out));
      error.writeSpan(wr, src);
      wr.flush();

      error.printStackTrace();
    }
  }

  private Parser(String s) throws SpanException {
    src = s;
    tokenizer = new Tokenizer(s);
    bump();
  }

  private Expr parse() throws SpanException {
    Expr e = parse(0);
    if (current != null)
      fatal("unexpected token " + current.type.name() + ", expected eof",
            current.spanBegin, current.spanBegin + current.spanLength);
    return e;
  }

  private Expr parse(int minPrec) throws SpanException {
    Expr e;

    Token t = current;
    if (t == null) {
      fatal("illegal eof", src.length(), src.length());
    }
    bump();

    switch (t.type) {
    case IDENT:
      if (current != null && current.type == TokenType.PAREN_OPEN) {
        bump();
        e = parse(0);
        if (current == null)
          fatal("expected PAREN_CLOSE, got eof", src.length(), src.length());
        if (current.type != TokenType.PAREN_CLOSE)
          fatal("expected PAREN_CLOSE, got " + current.type.name(),
                current.spanBegin, current.spanBegin + current.spanLength);
        bump();
        e = new FnExpr((String) t.content, e);
      } else {
        e = new VarExpr((String) t.content);
      }
      break;
    case LIT_NUM:
      e = new LiteralExpr((double) t.content);
      break;
    case PAREN_OPEN:
      e = new ParenExpr(parse(0));
      if (current == null)
        fatal("expected PAREN_CLOSE, got eof", src.length(), src.length());
      if (current.type != TokenType.PAREN_CLOSE)
        fatal("expected PAREN_CLOSE, got " + current.type.name(),
              current.spanBegin, current.spanBegin + current.spanLength);
      bump();
      break;
    case OP_PLUS:
      e = parse(2);
      break;
    case OP_MINUS:
      e = new NegateExpr(parse(2));
      break;
    default:
      fatal("unexpected token: " + t.type.name(),
            t.spanBegin, t.spanBegin + t.spanLength);
      return null;
    }

    for (;;) {
      t = current;
      if (t == null)
        break;
      if ((t.type == TokenType.OP_PLUS || t.type == TokenType.OP_MINUS)
          && minPrec > 0)
        break;
      if ((t.type == TokenType.OP_MUL || t.type == TokenType.OP_DIV)
          && minPrec > 1)
        break;
      if (t.type == TokenType.PAREN_CLOSE)
        break;

      e = parseWithLhs(e, minPrec);
    }

    return e;
  }

  private Expr parseWithLhs(Expr lhs, int minPrec) throws SpanException {
    Binop b;
    Token t = current;
    bump();
    switch (t.type) {
    case OP_PLUS:  b = Binop.ADD;      break;
    case OP_MINUS: b = Binop.SUBTRACT; break;
    case OP_MUL:   b = Binop.MULTIPLY; break;
    case OP_DIV:   b = Binop.DIVIDE;   break;
    default:
      fatal("unexpected token: " + t.type.name(),
            t.spanBegin, t.spanBegin + t.spanLength);
      return null;
    }
    if ((b == Binop.MULTIPLY || b == Binop.DIVIDE) && minPrec == 0)
      minPrec = 1;
    Expr rhs = parse(minPrec + 1);

    return new BinopExpr(b, lhs, rhs);
  }

  private void fatal(String msg, int spanBegin, int spanEnd)
      throws SpanException {
    throw new SpanException(msg, spanBegin, spanEnd - spanBegin);
  }

  private void bump() {
    if (!tokenizer.hasNext())
      current = null;
    else
      current = tokenizer.next();
  }

  String src;
  Tokenizer tokenizer;
  Token current;
}
