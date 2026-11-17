/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * File created on 24/09/2017
 */
package org.thunderdog.challegram.ui.camera;

// @MadeByLLM("gpt-6", id = "camerax-only")
public final class CameraFeatures {
  private CameraFeatures () { }

  public static final int FEATURE_FLASH_OFF = 1 << 10;
  public static final int FEATURE_FLASH_ON = 1 << 11;
  public static final int FEATURE_FLASH_AUTO = 1 << 12;
  public static final int FEATURE_FLASH_FAKE = 1 << 14;
}
