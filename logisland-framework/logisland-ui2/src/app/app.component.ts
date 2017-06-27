import { Component } from '@angular/core';

@Component({
  selector: 'li-root',
  template: `<div>
              <md-toolbar color="primary">
                <table>
                  <tr>
                    <td><a routerLink="/"><i style="color: white" class="fa fa-bars"></i></a></td>
                    <td >LogIsland</td>
                    <td width="100%" align="right"></td>
                  </tr>
                </table>
              </md-toolbar>
              <div>
                <table>
                  <tr valign="top">
                    <td class="leftbar">
                      <md-sidenav>
                        <md-nav-list>
                          <md-list-item>
                            <h2><a routerLink="/topic" routerLinkActive="active">Topics</a></h2>
                          </md-list-item>
                        </md-nav-list>
                      </md-sidenav>
                    </td>
                    <td>
                      <div style="text-align:center">
                        <router-outlet></router-outlet>
                      </div>
                    </td>
                  </tr>
                </table>
              </div>
            </div>`,
    styles: [`
      .leftbar {
        background-color: #eee;
        border-right: 1px solid #aaa;
        width: 33%;
        padding: 1em;
        padding-top: 0px;
      }
    `]
})
export class AppComponent {
  title = 'Logisland';
}
