.. Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
.. SPDX-License-Identifier: Apache-2.0

Authorization Middleware
########################

The authorization middleware is currently an :doc:`Early Access Feature in Labs status </support/status-definitions>`.

.. toctree::
   :hidden:

   ./oauth2

How to obtain an access token for a given Daml ledger that requires authorization is defined by the ledger operator.
The authorization middleware is an API that decouples Daml applications from these details.
The ledger operator can provide an authorization middleware that is suitable for their authentication and authorization mechanism.
The Daml SDK includes an implementation of an authorization middleware that supports `OAuth 2.0 Authorization Code Grant <https://oauth.net/2/grant-types/authorization-code/>`_.

Features
~~~~~~~~

The authorization middleware is designed to fulfill the following goals:

- Agnostic of the authentication and authorization protocol required by the identity and access management (IAM) system used by the ledger operator.
- Allow fine grained access control via Daml ledger claims.
- Support token refresh for long running clients that should not require user interaction.

Authorization Middleware API
~~~~~~~~~~~~~~~~~~~~~~~~~~~~

An implementation of the authorization middleware must provide the following API.

Obtain Access Token
*******************

The application contacts this endpoint to determine if the user is authenticated and authorized to access the given claims.
The application must forward any cookies that it itself received in the user's request.
The response will contain an access token and optionally a refresh token if the user is authenticated and authorized.

HTTP Request
============

- URL: ``/auth?claims=:claims``
- Method: ``GET``

where

- ``claims`` are the requested :ref:`Daml Ledger Claims`.

HTTP Response
=============

.. code-block:: json

    {
      "result":{"triggerId":"4d539e9c-b962-4762-be71-40a5c97a47a6"},
      "status":200
    }

Daml Ledger Claims
******************
