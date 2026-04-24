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
package com.axelor.apps.nbkrcurrencyrates.web;

import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.nbkrcurrencyrates.job.CurrencyRateSyncJob;
import com.axelor.apps.nbkrcurrencyrates.service.CurrencyRateService;
import com.axelor.apps.nbkrcurrencyrates.service.CurrencyRateUpdateResult;
import com.axelor.inject.Beans;
import com.axelor.meta.db.MetaSchedule;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import jakarta.inject.Singleton;

@Singleton
public class NbkrMetaScheduleController {

  private static final String NBKR_SCHEDULE_NAME = "NBKR Currency Rates Daily Sync";

  public void runNbkrScheduleNow(ActionRequest request, ActionResponse response) {
    try {
      MetaSchedule metaSchedule = request.getContext().asType(MetaSchedule.class);
      if (metaSchedule == null
          || !NBKR_SCHEDULE_NAME.equals(metaSchedule.getName())
          || !CurrencyRateSyncJob.class.getName().equals(metaSchedule.getJob())) {
        response.setAlert("This action is available only for NBKR currency rates schedule.");
        return;
      }

      CurrencyRateUpdateResult result = Beans.get(CurrencyRateService.class).updateRatesFromNbkr();
      response.setInfo(
          String.format(
              "NBKR rates updated for %s. Created: %d, updated: %d, total: %d.",
              result.date(), result.createdCount(), result.updatedCount(), result.totalCount()));
      response.setReload(true);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }
}
