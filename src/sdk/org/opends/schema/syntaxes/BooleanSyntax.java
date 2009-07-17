package org.opends.schema.syntaxes;

import static org.opends.server.schema.SchemaConstants.SYNTAX_BOOLEAN_NAME;
import static org.opends.server.schema.SchemaConstants.SYNTAX_BOOLEAN_OID;
import static org.opends.server.schema.SchemaConstants.SYNTAX_BOOLEAN_DESCRIPTION;
import org.opends.server.types.ByteSequence;
import org.opends.messages.MessageBuilder;
import static org.opends.messages.SchemaMessages.WARN_ATTR_SYNTAX_ILLEGAL_BOOLEAN;
import org.opends.schema.SchemaUtils;

/**
 * This class defines the Boolean attribute syntax, which only allows values of
 * "TRUE" or "FALSE" (although this implementation is more flexible and will
 * also allow "YES", "ON", or "1" instead of "TRUE", or "NO", "OFF", or "0"
 * instead of "FALSE").  Only equality matching is allowed by default for this
 * syntax.
 */
public class BooleanSyntax extends SyntaxDescription
{
  /**
   * Creates a new instance of this syntax.
   */
  public BooleanSyntax()
  {
    super(SYNTAX_BOOLEAN_OID, SYNTAX_BOOLEAN_NAME,
        SYNTAX_BOOLEAN_DESCRIPTION, SchemaUtils.RFC4512_ORIGIN);
  }


  /**
   * Indicates whether the provided value is acceptable for use in an attribute
   * with this syntax.  If it is not, then the reason may be appended to the
   * provided buffer.
   *
   * @param  value          The value for which to make the determination.
   * @param  invalidReason  The buffer to which the invalid reason should be
   *                        appended.
   *
   * @return  <CODE>true</CODE> if the provided value is acceptable for use with
   *          this syntax, or <CODE>false</CODE> if not.
   */
  public boolean valueIsAcceptable(ByteSequence value,
                                   MessageBuilder invalidReason)
  {
    String valueString = value.toString().toUpperCase();

    boolean returnValue = (valueString.equals("TRUE") ||
                           valueString.equals("YES") ||
                           valueString.equals("ON") ||
                           valueString.equals("1") ||
                           valueString.equals("FALSE") ||
                           valueString.equals("NO") ||
                           valueString.equals("OFF") ||
                           valueString.equals("0"));

    if (! returnValue)
    {
      invalidReason.append(WARN_ATTR_SYNTAX_ILLEGAL_BOOLEAN.get(
              value.toString()));
    }

    return returnValue;
  }

  public boolean isHumanReadable() {
    return true;
  }

}
