// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.lf.engine.trigger

class TriggerServiceTestAuthWithDb
    extends AbstractTriggerServiceTest
    with AbstractTriggerServiceTestWithDb
    with AbstractTriggerServiceTestAuthMiddleware {}
