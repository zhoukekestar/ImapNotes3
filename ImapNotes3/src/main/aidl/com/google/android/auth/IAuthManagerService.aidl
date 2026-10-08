// SPDX-License-Identifier: Apache-2.0
// Wire-compatible subset of microG's IAuthManagerService.
package com.google.android.auth;

import android.accounts.Account;
import android.os.Bundle;

interface IAuthManagerService {
    Bundle getTokenWithAccount(in Account account, String scope, in Bundle extras) = 4;
}
