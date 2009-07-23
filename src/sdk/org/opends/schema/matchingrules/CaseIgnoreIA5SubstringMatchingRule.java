package org.opends.schema.matchingrules;

import org.opends.schema.SchemaUtils;
import org.opends.schema.Schema;
import static org.opends.server.schema.SchemaConstants.SMR_CASE_IGNORE_IA5_NAME;
import static org.opends.server.schema.SchemaConstants.SMR_CASE_IGNORE_IA5_OID;
import static org.opends.server.schema.SchemaConstants.SYNTAX_SUBSTRING_ASSERTION_OID;
import static org.opends.schema.StringPrepProfile.TRIM;
import static org.opends.schema.StringPrepProfile.prepareUnicode;
import static org.opends.schema.StringPrepProfile.NO_CASE_FOLD;
import org.opends.server.types.ByteSequence;
import org.opends.server.types.ByteString;
import org.opends.server.util.ServerConstants;

import java.util.Collections;

/**
 * This class implements the caseIgnoreIA5SubstringsMatch matching rule defined
 * in RFC 2252.
 */
public class CaseIgnoreIA5SubstringMatchingRule
    extends SubstringMatchingRuleImplementation
{
  public CaseIgnoreIA5SubstringMatchingRule()
  {
    super(SMR_CASE_IGNORE_IA5_OID,
        Collections.singletonList(SMR_CASE_IGNORE_IA5_NAME),
        "",
        false,
        SYNTAX_SUBSTRING_ASSERTION_OID,
        SchemaUtils.RFC4512_ORIGIN);
  }

  public ByteSequence normalizeAttributeValue(Schema schema, ByteSequence value) {
    return normalize(TRIM, value);
  }

  @Override
  public ByteSequence normalizeSubInitialValue(Schema schema, ByteSequence value) {
    return normalize(false, value);
  }

  @Override
  public ByteSequence normalizeSubAnyValue(Schema schema, ByteSequence value) {
    return normalize(false, value);
  }

  @Override
  public ByteSequence normalizeSubFinalValue(Schema schema, ByteSequence value) {
    return normalize(false, value);
  }

  private ByteSequence normalize(boolean trim, ByteSequence value)
  {
    StringBuilder buffer = new StringBuilder();
    prepareUnicode(buffer, value, trim, NO_CASE_FOLD);

    int bufferLength = buffer.length();
    if (bufferLength == 0)
    {
      if (value.length() > 0)
      {
        // This should only happen if the value is composed entirely of spaces.
        // In that case, the normalized value is a single space.
        return ServerConstants.SINGLE_SPACE_VALUE;
      }
      else
      {
        // The value is empty, so it is already normalized.
        return ByteString.empty();
      }
    }


    // Replace any consecutive spaces with a single space.
    for (int pos = bufferLength-1; pos > 0; pos--)
    {
      if (buffer.charAt(pos) == ' ')
      {
        if (buffer.charAt(pos-1) == ' ')
        {
          buffer.delete(pos, pos+1);
        }
      }
    }

    return ByteString.valueOf(buffer.toString());
  }
}
