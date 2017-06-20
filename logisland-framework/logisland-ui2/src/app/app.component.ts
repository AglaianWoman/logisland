import { Component } from '@angular/core';

@Component({
  selector: 'li-root',
  template: `<div layout="row" layout-fill>
              <md-toolbar color="primary">
                <a routerLink="/"><fa name="bars"></fa></a>LogIsland
              </md-toolbar>

              <div layout="row">
                <li-leftbar flex="30"></li-leftbar>
                <div flex="70" style="text-align:center">
                  <router-outlet></router-outlet>
                </div>
              </div>
            </div>`,
})
export class AppComponent {
  title = 'Logisland';
}
