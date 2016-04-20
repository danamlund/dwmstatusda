dwmstatusda: dwmstatusda.c
	gcc -Os -Wall -pedantic -std=c99 dwmstatusda.c -lX11 -o dwmstatusda

echodwmstatus: echodwmstatus.c
	gcc -Os -Wall -pedantic -std=c99 echodwmstatus.c -lX11 -o echodwmstatus

run: clean dwmstatusda
	./dwmstatusda

run2: clean echodwmstatus
	./echodwmstatus

clean:
	rm -f dwmstatusda echodwmstatus
