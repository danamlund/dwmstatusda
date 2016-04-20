/**
 * echodwmstatus
 *
 * Copyright (C) 2013 by Dan Amlund Thomsen <dan@danamlund.dk>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 *
 */

/* echodwmstatus
 * 
 * Dan Amlund Thomsen
 * dan@danamlund.dk
 *
 * 2014-05-08: Version 1.0
 *
 * gcc -Os -Wall -pedantic -std=c99 echodwmstatus.c -lX11 -o echodwmstatus
 */

#define _BSD_SOURCE

#include <stdio.h>

#include <X11/Xlib.h>

int main(int argc, char **args) {
  Display *dpy = XOpenDisplay(NULL);
  if (NULL == dpy) {
    return 1;
  }

  char *window_name;
  if (!XFetchName(dpy, DefaultRootWindow(dpy), &window_name)) {
    return 2;
  }

  if (NULL == window_name) {
    return 3;
  }

  puts(window_name);

  XFree(window_name);
  
  return 0;
}
