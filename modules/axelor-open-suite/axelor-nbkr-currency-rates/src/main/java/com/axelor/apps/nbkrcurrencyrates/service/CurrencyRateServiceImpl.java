/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2005-2026 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.axelor.apps.nbkrcurrencyrates.service;

import com.axelor.apps.nbkrcurrencyrates.db.CurrencyRate;
import com.axelor.db.JPA;
import com.axelor.db.Query;
import jakarta.inject.Singleton;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

@Singleton
public class CurrencyRateServiceImpl implements CurrencyRateService {

  private static final Logger LOG = LoggerFactory.getLogger(CurrencyRateServiceImpl.class);

  private static final String NBKR_DAILY_XML_URL = "https://www.nbkr.kg/XML/daily.xml";
  private static final DateTimeFormatter NBKR_DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
  private static final int HTTP_MAX_ATTEMPTS = 3;
  private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration HTTP_REQUEST_TIMEOUT = Duration.ofSeconds(20);

  @Override
  public CurrencyRateUpdateResult updateRatesFromNbkr() {
    try {
      String xml = fetchDailyXml();
      ParsedDailyRates parsedDailyRates = parseDailyRates(xml);

      int createdCount = 0;
      int updatedCount = 0;

      for (RateLine line : parsedDailyRates.rateLines()) {
        UpsertStats upsertStats = upsertRateLine(parsedDailyRates.date(), line);
        createdCount += upsertStats.createdCount();
        updatedCount += upsertStats.updatedCount();
      }

      int totalCount = createdCount + updatedCount;
      LOG.info(
          "NBKR rates synchronized. date={}, created={}, updated={}, total={}",
          parsedDailyRates.date(),
          createdCount,
          updatedCount,
          totalCount);

      return new CurrencyRateUpdateResult(parsedDailyRates.date(), createdCount, updatedCount, totalCount);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to update NBKR currency rates", e);
    }
  }

  protected String fetchDailyXml() throws Exception {
    HttpClient httpClient = HttpClient.newBuilder().connectTimeout(HTTP_CONNECT_TIMEOUT).build();
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(NBKR_DAILY_XML_URL))
            .timeout(HTTP_REQUEST_TIMEOUT)
            .GET()
            .build();

    Exception lastException = null;
    for (int attempt = 1; attempt <= HTTP_MAX_ATTEMPTS; attempt++) {
      try {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
          return response.body();
        }
        throw new IllegalStateException("NBKR response status is " + response.statusCode());
      } catch (Exception e) {
        lastException = e;
        LOG.warn("NBKR request attempt {} failed: {}", attempt, e.getMessage());
      }
    }
    throw new IllegalStateException("NBKR request failed after " + HTTP_MAX_ATTEMPTS + " attempts", lastException);
  }

  protected ParsedDailyRates parseDailyRates(String xml) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);

    Document document =
        factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml == null ? "" : xml)));
    Element root = document.getDocumentElement();

    String dateValue = root.getAttribute("Date");
    LocalDate dailyDate = parseNbkrDate(dateValue);
    List<RateLine> lines = new ArrayList<>();

    NodeList currencyNodes = root.getElementsByTagName("Currency");
    if (currencyNodes.getLength() > 0) {
      for (int i = 0; i < currencyNodes.getLength(); i++) {
        Element currency = (Element) currencyNodes.item(i);
        String code = currency.getAttribute("ISOCode");
        String name = text(currency, "Name");
        int nominal = Integer.parseInt(text(currency, "Nominal").replace(" ", "").trim());
        BigDecimal rate = parseRate(text(currency, "Value"));

        if (code == null || code.isBlank()) {
          continue;
        }
        String resolvedName = (name == null || name.isBlank()) ? code : name;
        lines.add(new RateLine(code.trim(), resolvedName.trim(), nominal, rate));
      }
    } else {
      NodeList valuteNodes = root.getElementsByTagName("Valute");
      for (int i = 0; i < valuteNodes.getLength(); i++) {
        Element valute = (Element) valuteNodes.item(i);
        String code = text(valute, "CharCode");
        String name = text(valute, "Name");
        int nominal = Integer.parseInt(text(valute, "Nominal").replace(" ", "").trim());
        BigDecimal rate = parseRate(text(valute, "Value"));

        if (code == null || code.isBlank()) {
          continue;
        }
        lines.add(new RateLine(code.trim(), name == null ? "" : name.trim(), nominal, rate));
      }
    }

    return new ParsedDailyRates(dailyDate, lines);
  }

  protected UpsertStats upsertRateLine(LocalDate date, RateLine line) {
    final int[] counters = new int[] {0, 0};

    JPA.runInTransaction(
        () -> {
          Query<CurrencyRate> query =
              JPA.all(CurrencyRate.class)
                  .filter("self.code = :code AND self.rateDate = :date")
                  .bind("code", line.code())
                  .bind("date", date);

          CurrencyRate existing = query.fetchOne();
          if (existing == null) {
            CurrencyRate created = new CurrencyRate();
            created.setCode(line.code());
            created.setName(line.name());
            created.setSource(NBKR_DAILY_XML_URL);
            created.setNominal(line.nominal());
            created.setRate(line.rate());
            created.setRateDate(date);
            JPA.persist(created);
            counters[0] = 1;
          } else {
            existing.setName(line.name());
            existing.setSource(NBKR_DAILY_XML_URL);
            existing.setNominal(line.nominal());
            existing.setRate(line.rate());
            counters[1] = 1;
          }
        });

    return new UpsertStats(counters[0], counters[1]);
  }

  protected LocalDate parseNbkrDate(String value) {
    if (value == null || value.isBlank()) {
      return LocalDate.now();
    }
    try {
      return LocalDate.parse(value.trim(), NBKR_DATE_FORMAT);
    } catch (DateTimeParseException e) {
      return LocalDate.parse(value.trim());
    }
  }

  protected BigDecimal parseRate(String value) {
    String normalized = value == null ? "0" : value.replace(" ", "").replace(",", ".").trim();
    return new BigDecimal(normalized);
  }

  protected String text(Element parent, String tagName) {
    NodeList nodes = parent.getElementsByTagName(tagName);
    if (nodes.getLength() == 0 || nodes.item(0) == null) {
      return null;
    }
    return nodes.item(0).getTextContent();
  }

  protected record ParsedDailyRates(LocalDate date, List<RateLine> rateLines) {}

  protected record RateLine(String code, String name, int nominal, BigDecimal rate) {}

  protected record UpsertStats(int createdCount, int updatedCount) {}
}
