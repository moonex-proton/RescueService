package com.babenko.rescueservice.guided

import com.babenko.rescueservice.R

/**
 * Flows.kt — a declarative description of ready-made "dialog-action" scenarios.
 *
 * Each flow is a list of steps that describe:
 *  - what to say to the user (say)
 *  - what condition must be met for the transition (expect)
 *  - timeout for waiting and the text for re-speaking
 *
 * Here we describe 2 basic scenarios:
 *  1. enableAccessibility — enabling the accessibility service.
 *  2. enableNotifications — granting permission for notifications.
 *
 * ❗ These scenarios use basic matchers (AnyTag, AllTags) and do not depend on the Android API.
 */

object Flows {

    /**
     * Scenario for enabling the accessibility service.
     * Steps:
     * 1. Open the accessibility screen.
     * 2. Find and enable the RescueService service.
     * 3. Confirm enabling.
     */
    val enableAccessibility = GuidedFlow(
        id = "enable_accessibility",
        steps = listOf(
            GuidedStep(
                id = "open_accessibility",
                say = R.string.flow_enable_accessibility_step_1,
                expect = AnyTag(setOf("accessibility_settings")),
                timeoutMs = 10000,
                onTimeoutRepeatSay = R.string.flow_enable_accessibility_step_1_timeout
            ),
            GuidedStep(
                id = "select_service",
                say = R.string.flow_enable_accessibility_step_2,
                expect = AnyTag(setOf("service_list", "rescue_service_item")),
                timeoutMs = 15000,
                onTimeoutRepeatSay = R.string.flow_enable_accessibility_step_2_timeout
            ),
            GuidedStep(
                id = "confirm_enable",
                say = R.string.flow_enable_accessibility_step_3,
                expect = AnyTag(setOf("service_enabled", "toggle_on")),
                timeoutMs = 10000,
                onTimeoutRepeatSay = R.string.flow_enable_accessibility_step_3_timeout
            )
        )
    )

    /**
     * Scenario for granting notification permission.
     * Steps:
     * 1. Open the notification permission screen.
     * 2. Enable notifications for RescueService.
     */
    val enableNotifications = GuidedFlow(
        id = "enable_notifications",
        steps = listOf(
            GuidedStep(
                id = "open_notifications",
                say = R.string.flow_enable_notifications_step_1,
                expect = AnyTag(setOf("notification_settings")),
                timeoutMs = 10000,
                onTimeoutRepeatSay = R.string.flow_enable_notifications_step_1_timeout
            ),
            GuidedStep(
                id = "toggle_notifications",
                say = R.string.flow_enable_notifications_step_2,
                expect = AnyTag(setOf("notifications_enabled", "rescue_service_notifications")),
                timeoutMs = 10000,
                onTimeoutRepeatSay = R.string.flow_enable_notifications_step_2_timeout
            )
        )
    )
}
