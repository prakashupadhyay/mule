/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.extension.ws.api;

import static com.google.common.collect.ImmutableMap.copyOf;
import static com.google.common.collect.ImmutableMap.of;
import org.mule.runtime.core.message.BaseAttributes;
import org.mule.services.soap.api.message.SoapHeader;

import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Map;

/**
 * Contains the headers retrieved by the protocol after the request and the specific outbound SOAP headers retrieved
 * after performing a web service operation.
 *
 * @since 4.0
 */
public final class WscAttributes extends BaseAttributes {

  private final Map<String, String> protocolHeaders;
  private final List<SoapHeader> soapHeaders;

  public WscAttributes(List<SoapHeader> soapHeaders, Map<String, String> protocolHeaders) {
    this.soapHeaders = soapHeaders != null ? ImmutableList.copyOf(soapHeaders) : ImmutableList.of();
    this.protocolHeaders = protocolHeaders != null ? copyOf(protocolHeaders) : of();
  }

  public List<SoapHeader> getSoapHeaders() {
    return soapHeaders;
  }

  public Map<String, String> getProtocolHeaders() {
    return protocolHeaders;
  }
}
