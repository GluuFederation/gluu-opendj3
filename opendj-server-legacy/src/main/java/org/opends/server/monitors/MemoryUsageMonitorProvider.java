/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License, Version 1.0 only
 * (the "License").  You may not use this file except in compliance
 * with the License.
 *
 * You can obtain a copy of the license at legal-notices/CDDLv1_0.txt
 * or http://forgerock.org/license/CDDLv1.0.html.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at legal-notices/CDDLv1_0.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information:
 *      Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 *
 *
 *      Copyright 2008-2010 Sun Microsystems, Inc.
 *      Portions Copyright 2014-2015 ForgeRock AS
 */
package org.opends.server.monitors;



import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.forgerock.opendj.config.server.ConfigException;
import org.opends.server.admin.std.server.MemoryUsageMonitorProviderCfg;
import org.opends.server.api.MonitorProvider;
import org.opends.server.types.Attribute;
import org.opends.server.types.Attributes;
import org.opends.server.types.InitializationException;

/**
 * This class defines a monitor provider that reports information about
 * Directory Server memory usage.
 */
public class MemoryUsageMonitorProvider
       extends MonitorProvider<MemoryUsageMonitorProviderCfg>
       implements Runnable
{
  /** A map of the last GC counts seen by this monitor for calculating recent stats. */
  private HashMap<String,Long> lastGCCounts = new HashMap<>();
  /** A map of the last GC times seen by this monitor for calculating recent stats. */
  private HashMap<String,Long> lastGCTimes = new HashMap<>();
  /** A map of the most recent GC durations seen by this monitor. */
  private HashMap<String,Long> recentGCDurations = new HashMap<>();
  /** A map of the memory manager names to names that are safe for use in attribute names. */
  private HashMap<String,String> gcSafeNames = new HashMap<>();


  /** {@inheritDoc} */
  public void initializeMonitorProvider(
                   MemoryUsageMonitorProviderCfg configuration)
         throws ConfigException, InitializationException
  {
    scheduleUpdate(this, 0, 1, TimeUnit.SECONDS);
  }

  /** {@inheritDoc} */
  @Override
  public String getMonitorInstanceName()
  {
    return "JVM Memory Usage";
  }


  /** {@inheritDoc} */
  public void run()
  {
    for (GarbageCollectorMXBean gc :
         ManagementFactory.getGarbageCollectorMXBeans())
    {
      String gcName  = gc.getName();
      long   gcCount = gc.getCollectionCount();
      long   gcTime  = gc.getCollectionTime();

      long lastGCCount      = 0L;
      long lastGCTime       = 0L;
      long recentGCDuration = 0L;
      if (lastGCCounts.containsKey(gcName))
      {
        lastGCCount      = lastGCCounts.get(gcName);
        lastGCTime       = lastGCTimes.get(gcName);
        recentGCDuration = recentGCDurations.get(gcName);
      }

      if (gcCount > lastGCCount)
      {
        long recentGCCount = gcCount - lastGCCount;
        long recentGCTime  = gcTime  - lastGCTime;
        recentGCDuration   = recentGCTime / recentGCCount;
      }

      lastGCCounts.put(gcName, gcCount);
      lastGCTimes.put(gcName, gcTime);
      recentGCDurations.put(gcName, recentGCDuration);
    }
  }



  @Override
  public List<Attribute> getMonitorData()
  {
    ArrayList<Attribute> attrs = new ArrayList<>();

    for (GarbageCollectorMXBean gc :
         ManagementFactory.getGarbageCollectorMXBeans())
    {
      String gcName  = gc.getName();
      long   gcCount = gc.getCollectionCount();
      long   gcTime  = gc.getCollectionTime();

      long avgGCDuration = 0L;
      if (gcCount > 0)
      {
        avgGCDuration = gcTime / gcCount;
      }

      long recentGCDuration = 0L;
      if (recentGCDurations.containsKey(gcName))
      {
        recentGCDuration = recentGCDurations.get(gcName);
      }

      String safeName = gcSafeNames.get(gcName);
      if (safeName == null)
      {
        safeName = generateSafeName(gcName);
        gcSafeNames.put(gcName, safeName);
      }

      attrs.add(createAttribute(safeName + "-total-collection-count", gcCount));
      attrs.add(createAttribute(safeName + "-total-collection-duration", gcTime));
      attrs.add(createAttribute(safeName + "-average-collection-duration", avgGCDuration));
      attrs.add(createAttribute(safeName + "-recent-collection-duration", recentGCDuration));
    }

    for (MemoryPoolMXBean mp : ManagementFactory.getMemoryPoolMXBeans())
    {
      String      poolName        = mp.getName();
      MemoryUsage currentUsage    = mp.getUsage();
      MemoryUsage collectionUsage = mp.getCollectionUsage();

      String safeName = gcSafeNames.get(poolName);
      if (safeName == null)
      {
        safeName = generateSafeName(poolName);
        gcSafeNames.put(poolName, safeName);
      }

      long currentBytesUsed = currentUsage != null ? currentUsage.getUsed() : 0;
      attrs.add(createAttribute(safeName + "-current-bytes-used", currentBytesUsed));

      long collectionBytesUsed = collectionUsage != null ? collectionUsage.getUsed() : 0;
      attrs.add(createAttribute(safeName + "-bytes-used-after-last-collection", collectionBytesUsed));
    }

    return attrs;
  }

  private Attribute createAttribute(String name, Object value)
  {
    return Attributes.create(name, String.valueOf(value));
  }



  /**
   * Creates a "safe" version of the provided name, which is acceptable for
   * use as part of an attribute name.
   *
   * @param  name  The name for which to obtain the safe name.
   *
   * @return  The calculated safe name.
   */
  private String generateSafeName(String name)
  {
    StringBuilder buffer = new StringBuilder();
    boolean lastWasUppercase = false;
    boolean lastWasDash      = false;
    for (int i=0; i  < name.length(); i++)
    {
      char c = name.charAt(i);
      if (Character.isLetter(c))
      {
        if (Character.isUpperCase(c))
        {
          char lowerCaseCharacter = Character.toLowerCase(c);
          if (buffer.length() > 0 && !lastWasUppercase && !lastWasDash)
          {
            buffer.append('-');
          }

          buffer.append(lowerCaseCharacter);
          lastWasUppercase = true;
          lastWasDash = false;
        }
        else
        {
          buffer.append(c);
          lastWasUppercase = false;
          lastWasDash = false;
        }
      }
      else if (Character.isDigit(c))
      {
        buffer.append(c);
        lastWasUppercase = false;
        lastWasDash = false;
      }
      else if (c == ' ' || c == '_' || c == '-')
      {
        if (! lastWasDash)
        {
          buffer.append('-');
        }

        lastWasUppercase = false;
        lastWasDash = true;
      }
    }

    return buffer.toString();
  }
}

