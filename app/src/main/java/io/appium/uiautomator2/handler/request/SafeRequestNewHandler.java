/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.appium.uiautomator2.handler.request;

import static io.appium.uiautomator2.utils.AXWindowHelpers.refreshAccessibilityCache;
import static io.appium.uiautomator2.utils.ElementLocationHelpers.getXPathNodeMatch;
import static io.appium.uiautomator2.utils.ElementLocationHelpers.rewriteIdLocator;
import static io.appium.uiautomator2.utils.ModelUtils.toModel;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.uiautomator.StaleObjectException;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.UiObjectNotFoundException;

import com.google.gson.JsonSyntaxException;

import java.util.Objects;

import io.appium.uiautomator2.common.exceptions.ElementNotFoundException;
import io.appium.uiautomator2.common.exceptions.InvalidArgumentException;
import io.appium.uiautomator2.common.exceptions.NoSuchDriverException;
import io.appium.uiautomator2.common.exceptions.NotImplementedException;
import io.appium.uiautomator2.common.exceptions.StaleElementReferenceException;
import io.appium.uiautomator2.http.AppiumResponse;
import io.appium.uiautomator2.http.IHttpRequest;
import io.appium.uiautomator2.model.AccessibleUiObject;
import io.appium.uiautomator2.model.AndroidElement;
import io.appium.uiautomator2.model.AppiumUIA2Driver;
import io.appium.uiautomator2.model.By;
import io.appium.uiautomator2.model.Session;
import io.appium.uiautomator2.model.UiObject2Element;
import io.appium.uiautomator2.model.api.FindElementModel;
import io.appium.uiautomator2.model.internal.CustomUiDevice;
import io.appium.uiautomator2.model.internal.ElementsLookupStrategy;
import io.appium.uiautomator2.utils.ByUiAutomatorFinder;
import io.appium.uiautomator2.utils.Logger;
import io.appium.uiautomator2.utils.NodeInfoList;

public abstract class SafeRequestNewHandler extends BaseRequestHandler {

    public SafeRequestNewHandler(String mappedUri) {
        super(mappedUri);
    }

    @Override
    @NonNull
    public final AppiumResponse handle(IHttpRequest request) {
        Logger.info(String.format("%s command", getClass().getSimpleName()));

        String sessionId = getSessionId(request);

        try {
            return safeHandle(request);
        } catch (UiObjectNotFoundException e) {
            return new AppiumResponse(sessionId, new ElementNotFoundException(e));
        } catch (StaleObjectException e) {
            return new AppiumResponse(sessionId, new StaleElementReferenceException(e));
        } catch (JsonSyntaxException | IllegalArgumentException e) {
            return new AppiumResponse(sessionId, new InvalidArgumentException(e));
        } catch (Throwable e) {
            // Catching Errors seems like a bad idea in general but if we don't catch this,
            // Netty will catch it anyway.
            // The advantage of catching it here is that we can propagate the Error to clients.
            return new AppiumResponse(sessionId, e);
        }
    }

    protected AndroidElement findAndroidElement(IHttpRequest request) throws UiObjectNotFoundException {
        refreshAccessibilityCache();
        FindElementModel model = toModel(request, FindElementModel.class);
        final String method = model.strategy;
        final String selector = model.selector;
        final Boolean isSkip = model.skip==null?false:model.skip;
        final Long timeout = model.timeout==null?5000L:model.timeout;
        final By by = ElementsLookupStrategy.ofName(method).toNativeSelector(selector);
        AccessibleUiObject element = null;
        Long startTime = System.currentTimeMillis();
        while(element==null && System.currentTimeMillis()-startTime<timeout) {
            try {
                element = this.findAndroidElementByBy(by);
            }catch(ElementNotFoundException e){
                element=null;
            }catch(StaleObjectException e){
                element=null;
            }

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        if (element == null && !isSkip) {
            throw new ElementNotFoundException();
        }

        AndroidElement androidElement = new UiObject2Element(
                (UiObject2) element.getValue(), true, by, null);

        return androidElement;
    }

    @Nullable
    private AccessibleUiObject findAndroidElementByBy(By by) throws UiObjectNotFoundException {
        refreshAccessibilityCache();

        if (by instanceof By.ById) {
            String locator = rewriteIdLocator((By.ById) by);
            return CustomUiDevice.getInstance().findObject(androidx.test.uiautomator.By.res(locator));
        } else if (by instanceof By.ByAccessibilityId) {
            return CustomUiDevice.getInstance().findObject(androidx.test.uiautomator.By.desc(by.getElementLocator()));
        } else if (by instanceof By.ByClass) {
            return CustomUiDevice.getInstance().findObject(androidx.test.uiautomator.By.clazz(by.getElementLocator()));
        } else if (by instanceof By.ByXPath) {
            final NodeInfoList matchedNodes = getXPathNodeMatch(by.getElementLocator(), null, false);
            if (matchedNodes.isEmpty()) {
                throw new ElementNotFoundException();
            }
            return CustomUiDevice.getInstance().findObject(matchedNodes);
        } else if (by instanceof By.ByAndroidUiAutomator) {
            return new ByUiAutomatorFinder().findOne((By.ByAndroidUiAutomator) by);
        }

        throw new NotImplementedException(
                String.format("%s locator is not supported", by.getClass().getSimpleName())
        );
    }
}
