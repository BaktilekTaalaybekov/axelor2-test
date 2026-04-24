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
package com.axelor.apps.nbkrcurrencyrates.listener;

import com.axelor.db.JPA;
import com.axelor.event.Observes;
import com.axelor.events.StartupEvent;
import com.axelor.studio.db.App;
import com.axelor.studio.db.AppBase;
import com.google.inject.persist.Transactional;
import jakarta.annotation.Priority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NbkrAppMetadataInitListener {

  private static final Logger LOG = LoggerFactory.getLogger(NbkrAppMetadataInitListener.class);

  private static final String BASE_APP_CODE = "base";
  private static final String NBKR_APP_CODE = "nbkr-currency-rates";

  public void onStartup(@Observes @Priority(20) StartupEvent startupEvent) {
    initializeCoreAppMetadata();
  }

  @Transactional(rollbackOn = Exception.class)
  public void initializeCoreAppMetadata() {
    App baseApp = findOrCreateApp(BASE_APP_CODE, "Base", "Base configuration", 10);
    findOrCreateApp(NBKR_APP_CODE, "NBKR Currency Rates", "NBKR currency rates configuration.", 125);

    AppBase appBase = JPA.all(AppBase.class).filter("self.app.code = :code").bind("code", BASE_APP_CODE).fetchOne();
    if (appBase == null && baseApp != null) {
      AppBase created = new AppBase();
      created.setApp(baseApp);
      JPA.persist(created);
      LOG.info("Initialized missing AppBase metadata for app code={}", BASE_APP_CODE);
    }
  }

  protected App findOrCreateApp(String code, String name, String description, int sequence) {
    App app = JPA.all(App.class).filter("self.code = :code").bind("code", code).fetchOne();
    if (app != null) {
      return app;
    }

    App created = new App();
    created.setCode(code);
    created.setName(name);
    created.setDescription(description);
    created.setTypeSelect("standard");
    created.setActive(true);
    created.setSequence(sequence);
    JPA.persist(created);
    LOG.info("Initialized missing App metadata for app code={}", code);
    return created;
  }
}
